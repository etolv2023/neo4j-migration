package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.dryrun.DryRunOptions;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.dryrun.DryRunService;
import com.etolv.neo4jtool.engine.instance.InstanceRunners;
import com.etolv.neo4jtool.engine.build.BuildOptions;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.build.MergeOptions;
import com.etolv.neo4jtool.engine.build.MergeResult;
import com.etolv.neo4jtool.engine.build.MergeService;
import com.etolv.neo4jtool.engine.build.MergeTarget;
import com.etolv.neo4jtool.engine.build.SplitResult;
import com.etolv.neo4jtool.engine.build.SplitService;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import com.etolv.neo4jtool.web.EndpointService.Opened;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /**
     * Where a running job is: the phase it is in, how far that phase has got ({@code total} is -1 while it is not known),
     * when the phase and the whole job began, and which phase it is (the first is 1).
     */
    public record Progress(String phase, long done, long total, long phaseStartedMillis, long jobStartedMillis, int phaseNumber) {

        public Progress(String phase, long done, long total) {
            this(phase, done, total, System.currentTimeMillis(), System.currentTimeMillis(), 1);
        }

        /** 0 to 100, or -1 when the size of the phase is not known. */
        public int percent() {
            return total <= 0 ? -1 : (int) Math.min(100, done * 100 / total);
        }

        public long elapsedSeconds() {
            return Math.max(0, (System.currentTimeMillis() - jobStartedMillis) / 1000);
        }

        public long phaseSeconds() {
            return Math.max(0, (System.currentTimeMillis() - phaseStartedMillis) / 1000);
        }

        /** Items per second in this phase so far, or -1 when there is nothing to base it on. */
        public long perSecond() {
            long seconds = phaseSeconds();
            return done > 0 && seconds > 0 ? done / seconds : -1;
        }

        /** Seconds left in this phase at the current speed, or -1 when it cannot be told. */
        public long etaSeconds() {
            long rate = perSecond();
            return total > 0 && rate > 0 && done < total ? (total - done) / rate : -1;
        }
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
    private final MergeService mergeService = new MergeService();
    // As many at a time as the settings say (two by default): each job may start throw-away servers and hold selected ids.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Map<Long, Progress> running = new ConcurrentHashMap<>();
    // What the running job has reported, for the list of steps; and the same for the last job that failed, to show where.
    private final Map<Long, JobTimeline> timelines = new ConcurrentHashMap<>();
    private final Map<Long, JobTimeline> failedTimelines = new ConcurrentHashMap<>();

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

    /** What the running job of a migration has reported, or null when none runs. */
    JobTimeline timeline(long migrationId) {
        return timelines.get(migrationId);
    }

    /** What the last job of a migration that failed had reported, or null. */
    JobTimeline failedTimeline(long migrationId) {
        return failedTimelines.get(migrationId);
    }

    private void begin(long migrationId, JobTimeline.Stage stage) {
        failedTimelines.remove(migrationId);
        timelines.put(migrationId, new JobTimeline(stage));
        running.put(migrationId, new Progress("Queued", 0, -1));
    }

    /** Moves a running job to a new step; the time the whole job began is kept. */
    private void enter(long migrationId, String name) {
        Progress before = running.get(migrationId);
        long jobStarted = before == null ? System.currentTimeMillis() : before.jobStartedMillis();
        running.put(migrationId, new Progress(name, 0, -1, System.currentTimeMillis(), jobStarted, 1));
        JobTimeline timeline = timelines.get(migrationId);
        if (timeline != null) {
            timeline.mark(name);
        }
    }

    /** What the job prints, made readable (see {@link LogFilter}) and kept in its log. */
    private Consumer<String> sink(Migration migration) {
        JobTimeline timeline = timelines.get(migration.id());
        return new LogFilter(jobLog.sink(migration.scope()), timeline == null ? new ImportWatch() : timeline.importWatch());
    }

    private void finish(long migrationId) {
        running.remove(migrationId);
        timelines.remove(migrationId);
    }

    /** Queues a job; the number of jobs that run at the same time follows the settings as they are now. */
    private void submit(Runnable job) {
        AppSettings current = settings.get();
        int jobs = Math.max(1, current.maxJobs());
        if (jobs > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(jobs);
            executor.setCorePoolSize(jobs);
        } else {
            executor.setCorePoolSize(jobs);
            executor.setMaximumPoolSize(jobs);
        }
        IdSet.configure(current.idSetMemoryMb() * 1024L * 1024L, Path.of(current.workDir(), "idsets"));
        executor.submit(job);
    }

    /** How a build is done, by the settings: threads, import tuning, and the fast reader that checks the new database. */
    private BuildOptions buildOptions(AppSettings current) {
        return new BuildOptions(current.batchSize(), current.copySchema(), current.checkAfterBuild(), current.workers(),
                current.importThreads(), current.importMemory(), endpointService.fastReaders());
    }

    public void startPlan(Migration migration) {
        requireIdle(migration);
        migrations.start(migration.id(), Migration.State.PLANNING);
        jobLog.add(migration.scope(), "INFO", "Planning started");
        audit.record("plan_start", "#" + migration.id() + " " + migration.name());
        begin(migration.id(), JobTimeline.Stage.PLAN);
        submit(() -> runPlan(migration));
    }

    public void startDryRun(Migration migration) {
        requireIdle(migration);
        if (migration.planJson() == null) {
            throw new IllegalStateException("Make the plan first");
        }
        migrations.start(migration.id(), Migration.State.DRY_RUNNING);
        jobLog.add(migration.scope(), "INFO", "Dry run started");
        audit.record("dry_run_start", "#" + migration.id() + " " + migration.name());
        begin(migration.id(), JobTimeline.Stage.DRY_RUN);
        submit(() -> runDryRun(migration));
    }

    /**
     * A split writes a dump file of the selection. A merge needs a target that is not running (a stopped data directory
     * or a dump): it backs it up, builds the merged database, and swaps it in or writes it as a dump, by the settings.
     */
    public void startRun(Migration migration) {
        requireIdle(migration);
        if (migration.dryRunId() == null) {
            throw new IllegalStateException("Run the dry run first");
        }
        String gate = reports.find(migration.dryRunId()).map(ReportStore.Row::gate).orElse(null);
        if (gate == null || gate.equals("BLOCKED")) {
            throw new IllegalStateException("The dry run is blocked. Fix what it reports and run it again");
        }
        if (!migration.isSplit()) {
            Endpoint target = endpoints.find(migration.targetId())
                    .orElseThrow(() -> new IllegalStateException("The target '" + migration.targetName() + "' was deleted"));
            if (target.kind() == Endpoint.Kind.LIVE) {
                throw new IllegalStateException("A running server cannot be merged into. Stop it, then add its data directory "
                        + "(or a dump of it) as a database and make a new migration to it");
            }
        }
        if (!migration.isSplit() && migration.runJson() != null) {
            MergeResult previous = Json.read(migration.runJson(), MergeResult.class);
            if (previous.swapped() && previous.rolledBackAt() == null) {
                throw new IllegalStateException("This merge was swapped into the data directory. Roll it back first, "
                        + "or make a new migration from the new state");
            }
        }
        migrations.start(migration.id(), Migration.State.RUNNING);
        jobLog.add(migration.scope(), "INFO", "Run started");
        audit.record("run_start", "#" + migration.id() + " " + migration.name());
        begin(migration.id(), JobTimeline.Stage.RUN);
        submit(() -> {
            if (migration.isSplit()) {
                runSplit(migration);
            } else {
                runMerge(migration);
            }
        });
    }

    /** Undoes the swap of a finished merge. The server of the data directory must be stopped. */
    public void rollback(Migration migration) {
        requireIdle(migration);
        if (migration.isSplit() || migration.runJson() == null || migration.state() != Migration.State.RUN_DONE) {
            throw new IllegalStateException("There is no finished merge to roll back");
        }
        MergeResult result = Json.read(migration.runJson(), MergeResult.class);
        if (!result.swapped() || result.rolledBackAt() != null) {
            throw new IllegalStateException("This merge was not swapped in, or was already rolled back");
        }
        Consumer<String> out = jobLog.sink(migration.scope());
        jobLog.add(migration.scope(), "INFO", "Rollback started");
        audit.record("rollback_start", "#" + migration.id() + " " + migration.name());
        try {
            MergeResult rolledBack = mergeService.rollback(result, InstanceRunners.create(settings.get().instanceSettings()), out);
            migrations.ran(migration.id(), Json.write(rolledBack));
            jobLog.add(migration.scope(), "INFO", "Rollback finished: the old database is back in " + result.dataDirectory());
            audit.record("system", "rollback_done", "#" + migration.id());
        } catch (RuntimeException e) {
            log.warn("Rollback of migration {} failed", migration.id(), e);
            jobLog.add(migration.scope(), "ERROR", "Rollback failed: " + message(e));
            audit.record("system", "rollback_failed", "#" + migration.id());
            throw new IllegalStateException("The rollback failed: " + message(e), e);
        }
    }

    private static void requireIdle(Migration migration) {
        if (migration.state().busy()) {
            throw new IllegalStateException("This migration is already working");
        }
    }

    private void runPlan(Migration migration) {
        Consumer<String> out = sink(migration);
        try {
            Endpoint source = endpoints.find(migration.sourceId())
                    .orElseThrow(() -> new IllegalStateException("The source '" + migration.sourceName() + "' was deleted"));
            Endpoint target = migration.targetId() == null ? null : endpoints.find(migration.targetId())
                    .orElseThrow(() -> new IllegalStateException("The target '" + migration.targetName() + "' was deleted"));
            SelectionConfig selection = SelectionConfigParser.parse(migration.selectionYaml());
            enter(migration.id(), "Opening '" + source.name() + "'");
            try (Opened from = endpointService.open(source, out)) {
                Opened to = null;
                try {
                    if (target != null) {
                        enter(migration.id(), "Opening '" + target.name() + "'");
                        to = endpointService.open(target, out);
                    }
                    enter(migration.id(), "Counting");
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
            finish(migration.id());
        }
    }

    private void runDryRun(Migration migration) {
        Consumer<String> out = sink(migration);
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

            enter(migration.id(), "Opening '" + source.name() + "'");
            DryRunReport report;
            try (Opened from = endpointService.open(source, out)) {
                Opened to = null;
                try {
                    if (target != null) {
                        enter(migration.id(), "Opening '" + target.name() + "'");
                        to = endpointService.open(target, out);
                    }
                    report = dryRunService.run(from.client(), to == null ? null : to.client(), selection, options, progress(migration, out));
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
            finish(migration.id());
        }
    }

    private void runSplit(Migration migration) {
        Consumer<String> out = sink(migration);
        try {
            Endpoint source = endpoints.find(migration.sourceId())
                    .orElseThrow(() -> new IllegalStateException("The source '" + migration.sourceName() + "' was deleted"));
            SelectionConfig selection = SelectionConfigParser.parse(migration.selectionYaml());
            AppSettings current = settings.get();
            Path output = Path.of(current.outputDir(), fileName(migration, !current.splitAsDirectory()));
            BuildOptions options = buildOptions(current).withDirectoryOutput(current.splitAsDirectory());
            enter(migration.id(), "Opening '" + source.name() + "'");
            SplitResult result;
            try (Opened from = endpointService.open(source, out)) {
                result = splitService.run(from.client(), from.fast(), selection, InstanceRunners.create(current.instanceSettings()),
                        Path.of(current.workDir()), output, options, progress(migration, out), out);
            }
            migrations.ran(migration.id(), Json.write(result));
            jobLog.add(migration.scope(), "INFO", "Run finished: " + result.outputFile());
            audit.record("system", "run_done", "#" + migration.id() + " " + result.outputFile());
        } catch (Throwable e) {
            fail(migration, Migration.State.DRY_RUN_DONE, "Run", e);
        } finally {
            finish(migration.id());
        }
    }

    private void runMerge(Migration migration) {
        Consumer<String> out = sink(migration);
        try {
            Endpoint source = endpoints.find(migration.sourceId())
                    .orElseThrow(() -> new IllegalStateException("The source '" + migration.sourceName() + "' was deleted"));
            Endpoint target = endpoints.find(migration.targetId())
                    .orElseThrow(() -> new IllegalStateException("The target '" + migration.targetName() + "' was deleted"));
            SelectionConfig selection = SelectionConfigParser.parse(migration.selectionYaml());
            AppSettings current = settings.get();
            MergeTarget mergeTarget = target.kind() == Endpoint.Kind.STOPPED
                    ? MergeTarget.dataDirectory(Path.of(target.path()), target.database())
                    : MergeTarget.dump(Path.of(target.path()), target.database());
            Path output = Path.of(current.outputDir(), fileName(migration, true));
            MergeOptions options = new MergeOptions(buildOptions(current), current.mergeSwap());
            enter(migration.id(), "Opening '" + source.name() + "'");
            MergeResult result;
            try (Opened from = endpointService.open(source, out)) {
                result = mergeService.run(from.client(), from.fast(), selection, mergeTarget,
                        InstanceRunners.create(current.instanceSettings()),
                        Path.of(current.workDir()), Path.of(current.backupDir()), output, options, progress(migration, out), out);
            }
            migrations.ran(migration.id(), Json.write(result));
            jobLog.add(migration.scope(), "INFO", result.swapped()
                    ? "Run finished: the merged database is in " + result.dataDirectory() + "; the old one is in " + result.keepName()
                    : "Run finished: " + result.outputFile());
            audit.record("system", "run_done", "#" + migration.id());
        } catch (Throwable e) {
            fail(migration, Migration.State.DRY_RUN_DONE, "Run", e);
        } finally {
            finish(migration.id());
        }
    }

    private static String fileName(Migration migration, boolean dump) {
        String slug = migration.name().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return (slug.isEmpty() ? "split" : slug) + "-" + migration.id() + "-" + stamp + (dump ? ".dump" : "");
    }

    private DryRunProgress progress(Migration migration, Consumer<String> out) {
        long migrationId = migration.id();
        Progress before = running.get(migrationId);
        long jobStarted = before == null ? System.currentTimeMillis() : before.jobStartedMillis();
        return new DryRunProgress() {
            private volatile String phase = "Starting";
            private volatile long phaseStarted = System.currentTimeMillis();
            private volatile int phaseNumber;
            private volatile long lastLogged;

            @Override
            public void phase(String name) {
                phase = name;
                phaseStarted = System.currentTimeMillis();
                phaseNumber++;
                running.put(migrationId, new Progress(name, 0, -1, phaseStarted, jobStarted, phaseNumber));
                JobTimeline timeline = timelines.get(migrationId);
                if (timeline != null) {
                    timeline.mark(name);
                }
                jobLog.add(migration.scope(), "STEP", name);
            }

            @Override
            public void progress(long done, long total) {
                running.put(migrationId, new Progress(phase, done, total, phaseStarted, jobStarted, phaseNumber));
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
        JobTimeline timeline = timelines.get(migration.id());
        if (timeline != null) {
            timeline.end();
            failedTimelines.put(migration.id(), timeline);
        }
        migrations.failed(migration.id(), backTo, message);
        jobLog.add(migration.scope(), "ERROR", what + " failed: " + message);
        audit.record("system", what.toLowerCase().replace(' ', '_') + "_failed", "#" + migration.id());
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
