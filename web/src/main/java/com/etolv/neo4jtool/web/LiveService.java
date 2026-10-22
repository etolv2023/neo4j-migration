package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.build.MergeResult;
import com.etolv.neo4jtool.engine.build.Phase;
import com.etolv.neo4jtool.engine.build.SplitResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * What the pages show live: the running job of a migration (where it is, its steps, the new lines of its log) and the
 * resources of the machine. Only reads; nothing here can change a job.
 */
@Service
public class LiveService {

    public record Stage(String name, int number, int of, int percent, long elapsedSeconds, long etaSeconds) {
    }

    public record LogLine(long id, String time, String cls, boolean important, String text) {
    }

    /**
     * @param steps    the steps of the three stages that have steps: plan, dry_run and run
     * @param logFrom  the id of the line the latest run began with; a page that has an older one starts its log again
     * @param log      the lines after the one the page said it has (or the whole latest run)
     */
    public record Job(long id, boolean busy, String state, String label, String error, long elapsedSeconds, String phase,
                      long phaseSeconds, int percent, long done, long total, long perSecond, long etaSeconds, int stepNumber,
                      int stepCount, Stage stage, Map<String, List<RunSteps.Step>> steps, long logFrom, List<LogLine> log,
                      long lastLogId) {
    }

    public record Live(Job job, Resources.Snapshot resources, int intervalSeconds) {
    }

    private final MigrationStore migrations;
    private final EndpointStore endpoints;
    private final MigrationJobs jobs;
    private final JobLog jobLog;
    private final SettingsStore settings;
    private final Resources resources;

    public LiveService(MigrationStore migrations, EndpointStore endpoints, MigrationJobs jobs, JobLog jobLog,
                       SettingsStore settings, Resources resources) {
        this.migrations = migrations;
        this.endpoints = endpoints;
        this.jobs = jobs;
        this.jobLog = jobLog;
        this.settings = settings;
        this.resources = resources;
    }

    /** @param migrationId the migration whose job is shown, or null for the resources only; @param after the last log line the page has */
    public Live snapshot(Long migrationId, long after) {
        AppSettings current = settings.get();
        Job job = migrationId == null ? null : migrations.find(migrationId).map(m -> job(m, current, after)).orElse(null);
        return new Live(job, resources.read(), current.liveSeconds());
    }

    public String json(Long migrationId, long after) {
        return Json.write(snapshot(migrationId, after));
    }

    private Job job(Migration m, AppSettings current, long after) {
        boolean busy = m.state().busy();
        MigrationJobs.Progress progress = jobs.progress(m.id());
        JobTimeline live = jobs.timeline(m.id());
        JobTimeline failed = jobs.failedTimeline(m.id());
        RunSteps.Context context = context(m, current);

        Map<String, List<RunSteps.Step>> steps = new LinkedHashMap<>();
        steps.put("plan", stageSteps(JobTimeline.Stage.PLAN, RunSteps.plan(context), live, failed, m.planJson() != null, null));
        steps.put("dry_run", stageSteps(JobTimeline.Stage.DRY_RUN, RunSteps.dryRun(context), live, failed, m.dryRunId() != null, null));
        steps.put("run", stageSteps(JobTimeline.Stage.RUN, m.isSplit() ? RunSteps.split(context) : RunSteps.merge(context), live,
                failed, m.runJson() != null, finishedPhases(m)));

        // Numbers of what is running now go on its step and on the card at the top.
        String active = live == null ? null : switch (live.stage()) {
            case PLAN -> "plan";
            case DRY_RUN -> "dry_run";
            case RUN -> "run";
        };
        Stage importStage = null;
        int percent = progress == null ? -1 : progress.percent();
        int stepNumber = 0;
        int stepCount = 0;
        if (active != null) {
            List<RunSteps.Step> list = new ArrayList<>(steps.get(active));
            stepCount = list.size();
            for (int i = 0; i < list.size(); i++) {
                RunSteps.Step step = list.get(i);
                if (step.state() != RunSteps.State.RUNNING) {
                    continue;
                }
                stepNumber = i + 1;
                ImportWatch.Stage stage = live.importWatch().stage();
                if (step.title().startsWith("Build the new database") && stage != null) {
                    importStage = new Stage(stage.name(), stage.number(), stage.of(), stage.percent(), stage.elapsedSeconds(),
                            stage.etaSeconds());
                    percent = stage.percent();
                    list.set(i, step.with(RunSteps.State.RUNNING, "Step " + stage.number() + " of " + stage.of() + ": " + stage.name(),
                            stage.percent()));
                } else if (progress != null) {
                    list.set(i, step.with(RunSteps.State.RUNNING, step.detail(), progress.percent()));
                }
            }
            steps.put(active, list);
        }

        String scope = m.scope();
        long runStart = jobLog.lastRunStart(scope);
        List<JobLog.Line> lines = after > 0 && after >= runStart ? jobLog.after(scope, after, 500) : jobLog.from(scope, runStart, 400);
        long lastId = after;
        List<LogLine> log = new ArrayList<>();
        for (JobLog.Line line : lines) {
            log.add(new LogLine(line.id(), LogView.time(line.at()), LogView.cls(line), LogView.important(line), line.message()));
            lastId = Math.max(lastId, line.id());
        }
        return new Job(m.id(), busy, m.state().name(), m.state().label(), m.lastError(),
                progress == null ? 0 : progress.elapsedSeconds(), progress == null ? null : progress.phase(),
                progress == null ? 0 : progress.phaseSeconds(), percent, progress == null ? 0 : progress.done(),
                progress == null ? -1 : progress.total(), progress == null ? -1 : progress.perSecond(),
                importStage != null ? importStage.etaSeconds() : progress == null ? -1 : progress.etaSeconds(), stepNumber,
                stepCount, importStage, steps, runStart, log, lastId);
    }

    private static List<RunSteps.Step> stageSteps(JobTimeline.Stage stage, List<RunSteps.Def> defs, JobTimeline live,
                                                  JobTimeline failed, boolean done, List<Phase> phases) {
        long now = System.currentTimeMillis();
        if (live != null && live.stage() == stage) {
            return RunSteps.of(defs, live.marks(), now, false);
        }
        if (failed != null && failed.stage() == stage) {
            return RunSteps.of(defs, failed.marks(), failed.endedAt() > 0 ? failed.endedAt() : now, true);
        }
        if (done && phases != null) {
            return RunSteps.finished(defs, phases);
        }
        return RunSteps.all(defs, done);
    }

    /** How long each step of the finished run took, or null when the result cannot be read. */
    private static List<Phase> finishedPhases(Migration m) {
        if (m.runJson() == null) {
            return null;
        }
        try {
            return m.isSplit() ? Json.read(m.runJson(), SplitResult.class).phases()
                    : Json.read(m.runJson(), MergeResult.class).phases();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private RunSteps.Context context(Migration m, AppSettings current) {
        boolean stopped = m.targetId() != null
                && endpoints.find(m.targetId()).map(e -> e.kind() == Endpoint.Kind.STOPPED).orElse(false);
        return new RunSteps.Context(m.sourceName(), m.targetName(), stopped && current.mergeSwap(), current.splitAsDirectory(),
                current.checkAfterBuild(), current.analyseRelationships());
    }
}
