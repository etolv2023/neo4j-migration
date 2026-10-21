package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.dryrun.DryRunOptions;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.dryrun.DryRunService;
import com.etolv.neo4jtool.engine.instance.InstanceRunners;
import com.etolv.neo4jtool.engine.split.SplitOptions;
import com.etolv.neo4jtool.engine.split.SplitResult;
import com.etolv.neo4jtool.engine.split.SplitService;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import com.etolv.neo4jtool.web.EndpointService.Opened;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Runs the plan and the dry run of a migration in the background, writing what happens to the job log. */
@Service
public class MigrationJobs {

    private static final Logger log = LoggerFactory.getLogger(MigrationJobs.class);

    public record Progress(String phase, long done, long total) {
    }

    private final MigrationStore migrations;
    private final EndpointStore endpoints;
    private final EndpointService endpointService;
    private final SettingsStore settings;
    private final ReportStore reports;
    private final Planner planner;
    private final JobLog jobLog;
    private final AuditLog audit;
    private final DryRunService dryRunService = new DryRunService();
    private final SplitService splitService = new SplitService();
    // Two at a time: each job may start throw-away servers and hold all selected ids in memory.
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<Long, Progress> running = new ConcurrentHashMap<>();

    public MigrationJobs(MigrationStore migrations, EndpointStore endpoints, EndpointService endpointService,
                         SettingsStore settings, ReportStore reports, Planner planner, JobLog jobLog, AuditLog audit) {
        this.migrations = migrations;
        this.endpoints = endpoints;
        this.endpointService = endpointService;
        this.settings = settings;
        this.reports = reports;
        this.planner = planner;
        this.jobLog = jobLog;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void cleanUp() {
        int jobs = migrations.failInterrupted() + reports.failInterrupted();
        if (jobs > 0) {
            log.warn("{} job(s) were still marked running at start-up and were stopped", jobs);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public Progress progress(long migrationId) {
        return running.get(migrationId);
    }

    public void startPlan(Migration migration) {
        requireIdle(migration);
        migrations.start(migration.id(), Migration.State.PLANNING);
        jobLog.add(migration.scope(), "INFO", "Planning started");
        audit.record("plan_start", "#" + migration.id() + " " + migration.name());
        running.put(migration.id(), new Progress("Queued", 0, -1));
        executor.submit(() -> runPlan(migration));
    }

    public void startDryRun(Migration migration) {
        requireIdle(migration);
        if (migration.planJson() == null) {
            throw new IllegalStateException("Make the plan first");
        }
        migrations.start(migration.id(), Migration.State.DRY_RUNNING);
        jobLog.add(migration.scope(), "INFO", "Dry run started");
        audit.record("dry_run_start", "#" + migration.id() + " " + migration.name());
        running.put(migration.id(), new Progress("Queued", 0, -1));
        executor.submit(() -> runDryRun(migration));
    }

    /** Splits the selection into a new database and writes it as a dump file. Merging is not part of this version. */
    public void startRun(Migration migration) {
        requireIdle(migration);
        if (!migration.isSplit()) {
            throw new IllegalStateException("Merging into an existing database is not available in this version");
        }
        if (migration.dryRunId() == null) {
            throw new IllegalStateException("Run the dry run first");
        }
        String gate = reports.find(migration.dryRunId()).map(ReportStore.Row::gate).orElse(null);
        if (gate == null || gate.equals("BLOCKED")) {
            throw new IllegalStateException("The dry run is blocked. Fix what it reports and run it again");
        }
        migrations.start(migration.id(), Migration.State.RUNNING);
        jobLog.add(migration.scope(), "INFO", "Run started");
        audit.record("run_start", "#" + migration.id() + " " + migration.name());
        running.put(migration.id(), new Progress("Queued", 0, -1));
        executor.submit(() -> runSplit(migration));
    }

    private static void requireIdle(Migration migration) {
        if (migration.state().busy()) {
            throw new IllegalStateException("This migration is already working");
        }
    }

    private void runPlan(Migration migration) {
        Consumer<String> out = jobLog.sink(migration.scope());
        try {
            Endpoint source = endpoints.find(migration.sourceId())
                    .orElseThrow(() -> new IllegalStateException("The source '" + migration.sourceName() + "' was deleted"));
            Endpoint target = migration.targetId() == null ? null : endpoints.find(migration.targetId())
                    .orElseThrow(() -> new IllegalStateException("The target '" + migration.targetName() + "' was deleted"));
            SelectionConfig selection = SelectionConfigParser.parse(migration.selectionYaml());
            running.put(migration.id(), new Progress("Opening '" + source.name() + "'", 0, -1));
            try (Opened from = endpointService.open(source, out)) {
                Opened to = null;
                try {
                    if (target != null) {
                        running.put(migration.id(), new Progress("Opening '" + target.name() + "'", 0, -1));
                        to = endpointService.open(target, out);
                    }
                    running.put(migration.id(), new Progress("Counting", 0, -1));
                    MigrationPlan plan = planner.plan(source, from.client(), target, to == null ? null : to.client(), selection);
                    migrations.planned(migration.id(), Json.write(plan));
                } finally {
                    if (to != null) {
                        to.close();
                    }
                }
            }
            jobLog.add(migration.scope(), "INFO", "Plan ready");
            audit.record("system", "plan_done", "#" + migration.id());
        } catch (Throwable e) {
            fail(migration, Migration.State.DRAFT, "Planning", e);
        } finally {
            running.remove(migration.id());
        }
    }

    private void runDryRun(Migration migration) {
        Consumer<String> out = jobLog.sink(migration.scope());
        long reportId = -1;
        try {
            Endpoint source = endpoints.find(migration.sourceId())
                    .orElseThrow(() -> new IllegalStateException("The source '" + migration.sourceName() + "' was deleted"));
            Endpoint target = migration.targetId() == null ? null : endpoints.find(migration.targetId())
                    .orElseThrow(() -> new IllegalStateException("The target '" + migration.targetName() + "' was deleted"));
            SelectionConfig selection = SelectionConfigParser.parse(migration.selectionYaml());
            AppSettings current = settings.get();
            DryRunOptions options = new DryRunOptions(current.batchSize(), current.sampleSize(), current.analyseRelationships());
            reportId = reports.begin(migration.id(), migration.sourceName(), migration.targetName(), migration.selectionYaml(), options);

            running.put(migration.id(), new Progress("Opening '" + source.name() + "'", 0, -1));
            DryRunReport report;
            try (Opened from = endpointService.open(source, out)) {
                Opened to = null;
                try {
                    if (target != null) {
                        running.put(migration.id(), new Progress("Opening '" + target.name() + "'", 0, -1));
                        to = endpointService.open(target, out);
                    }
                    report = dryRunService.run(from.client(), to == null ? null : to.client(), selection, options, progress(migration.id(), out));
                } finally {
                    if (to != null) {
                        to.close();
                    }
                }
            }
            reports.finish(reportId, report);
            migrations.dryRunDone(migration.id(), reportId);
            jobLog.add(migration.scope(), "INFO", "Dry run finished: " + report.gate());
            audit.record("system", "dry_run_done", "#" + migration.id() + " " + report.gate());
        } catch (Throwable e) {
            if (reportId > 0) {
                reports.fail(reportId, message(e));
            }
            fail(migration, Migration.State.PLANNED, "Dry run", e);
        } finally {
            running.remove(migration.id());
        }
    }

    private void runSplit(Migration migration) {
        Consumer<String> out = jobLog.sink(migration.scope());
        try {
            Endpoint source = endpoints.find(migration.sourceId())
                    .orElseThrow(() -> new IllegalStateException("The source '" + migration.sourceName() + "' was deleted"));
            SelectionConfig selection = SelectionConfigParser.parse(migration.selectionYaml());
            AppSettings current = settings.get();
            Path output = Path.of(current.outputDir(), fileName(migration));
            SplitOptions options = new SplitOptions(current.batchSize(), current.copySchema(), current.checkAfterBuild());
            running.put(migration.id(), new Progress("Opening '" + source.name() + "'", 0, -1));
            SplitResult result;
            try (Opened from = endpointService.open(source, out)) {
                result = splitService.run(from.client(), selection, InstanceRunners.create(current.instanceSettings()),
                        Path.of(current.workDir()), output, options, progress(migration.id(), out), out);
            }
            migrations.ran(migration.id(), Json.write(result));
            jobLog.add(migration.scope(), "INFO", "Run finished: " + result.outputFile());
            audit.record("system", "run_done", "#" + migration.id() + " " + result.outputFile());
        } catch (Throwable e) {
            fail(migration, Migration.State.DRY_RUN_DONE, "Run", e);
        } finally {
            running.remove(migration.id());
        }
    }

    private static String fileName(Migration migration) {
        String slug = migration.name().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return (slug.isEmpty() ? "split" : slug) + "-" + migration.id() + "-" + stamp + ".dump";
    }

    private DryRunProgress progress(long migrationId, Consumer<String> out) {
        return new DryRunProgress() {
            private volatile String phase = "Starting";
            private volatile long lastLogged;

            @Override
            public void phase(String name) {
                phase = name;
                running.put(migrationId, new Progress(name, 0, -1));
                out.accept(name);
            }

            @Override
            public void progress(long done, long total) {
                running.put(migrationId, new Progress(phase, done, total));
                long now = System.currentTimeMillis();
                if (now - lastLogged > 10_000) {
                    lastLogged = now;
                    out.accept(phase + ": " + done + (total >= 0 ? " / " + total : ""));
                }
            }
        };
    }

    private void fail(Migration migration, Migration.State backTo, String what, Throwable e) {
        log.warn("{} of migration {} failed", what, migration.id(), e);
        String message = message(e);
        migrations.failed(migration.id(), backTo, message);
        jobLog.add(migration.scope(), "ERROR", what + " failed: " + message);
        audit.record("system", what.toLowerCase().replace(' ', '_') + "_failed", "#" + migration.id());
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
