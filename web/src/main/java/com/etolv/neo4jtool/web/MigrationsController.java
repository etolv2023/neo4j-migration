package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import com.etolv.neo4jtool.engine.build.MergeResult;
import com.etolv.neo4jtool.engine.build.RenumberOptions;
import com.etolv.neo4jtool.engine.build.SplitResult;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class MigrationsController {

    private final MigrationStore migrations;
    private final EndpointStore endpoints;
    private final ReportStore reports;
    private final MigrationJobs jobs;
    private final JobLog jobLog;
    private final SettingsStore settings;
    private final AuditLog audit;

    public MigrationsController(MigrationStore migrations, EndpointStore endpoints, ReportStore reports, MigrationJobs jobs,
                                JobLog jobLog, SettingsStore settings, AuditLog audit) {
        this.migrations = migrations;
        this.endpoints = endpoints;
        this.reports = reports;
        this.jobs = jobs;
        this.jobLog = jobLog;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/")
    String home() {
        return "redirect:/migrations";
    }

    @GetMapping("/migrations")
    String list(Model model) {
        model.addAttribute("migrations", migrations.all());
        return "migrations";
    }

    @GetMapping("/migrations/new")
    String form(Model model) {
        fillForm(model, "", null, SelectionForm.empty(settings.get().includeNeighbours()));
        model.addAttribute("renumber", settings.get().renumber());
        return "migration-new";
    }

    @PostMapping("/migrations")
    String create(@RequestParam(defaultValue = "") String name, @RequestParam(required = false) Long source,
                  @RequestParam(required = false) Long target, @ModelAttribute SelectionForm selection,
                  @RequestParam(defaultValue = "false") boolean renumber,
                  @RequestParam(defaultValue = "ABOVE_HIGHEST") RenumberOptions.Allocation renumberAllocation,
                  @RequestParam(defaultValue = "false") boolean renumberLists,
                  @RequestParam(defaultValue = "false") boolean renumberIncludeNewer, Model model) {
        RenumberOptions chosen = new RenumberOptions(renumber, renumberAllocation, renumberLists, renumberIncludeNewer, Set.of());
        try {
            if (name.isBlank()) {
                throw new IllegalArgumentException("Give the migration a name");
            }
            Endpoint from = endpoints.find(source == null ? -1 : source)
                    .orElseThrow(() -> new IllegalArgumentException("Choose where the data comes from"));
            Endpoint to = target == null ? null : endpoints.find(target)
                    .orElseThrow(() -> new IllegalArgumentException("The target no longer exists"));
            if (to != null && from.sameDatabaseAs(to)) {
                throw new IllegalArgumentException("From and To are the same database");
            }
            String yaml = SelectionConfigParser.toYaml(selection.toConfig(settings.get().orgProperty()));
            long id = migrations.create(name.strip(), from, to, yaml, to != null && chosen.enabled() ? Json.write(chosen) : null);
            audit.record("migration_create", "#" + id + " " + name.strip());
            return "redirect:/migrations/" + id;
        } catch (IllegalArgumentException e) {
            fillForm(model, name, target, selection);
            model.addAttribute("renumber", chosen);
            model.addAttribute("sourceId", source);
            model.addAttribute("error", e.getMessage());
            return "migration-new";
        }
    }

    @GetMapping("/migrations/{id}")
    String show(@PathVariable long id, Model model) {
        Migration migration = find(id);
        model.addAttribute("migration", migration);
        model.addAttribute("plan", migration.planJson() == null ? null : Json.read(migration.planJson(), MigrationPlan.class));
        model.addAttribute("progress", jobs.progress(id));
        model.addAttribute("renumber", migration.renumber());
        // the log of the latest run only; the Logs page has all of them
        long runStart = jobLog.lastRunStart(migration.scope());
        java.util.List<JobLog.Line> log = jobLog.from(migration.scope(), runStart, 400);
        model.addAttribute("log", log);
        model.addAttribute("logFrom", runStart);
        model.addAttribute("logAfter", log.isEmpty() ? 0 : log.getLast().id());
        model.addAttribute("liveMigration", id);
        model.addAttribute("liveBusy", migration.state().busy());
        model.addAttribute("failure", migration.lastError() == null ? null
                : Failure.explain(migration.lastError(), Failure.Stage.of(jobLog.messageAt(runStart)), jobLog.lastStep(migration.scope(), runStart)));
        model.addAttribute("dryRun", migration.dryRunId() == null ? null : reports.find(migration.dryRunId()).orElse(null));
        model.addAttribute("result", migration.runJson() == null || !migration.isSplit() ? null
                : Json.read(migration.runJson(), SplitResult.class));
        model.addAttribute("merge", migration.runJson() == null || migration.isSplit() ? null
                : Json.read(migration.runJson(), MergeResult.class));
        model.addAttribute("targetKind", migration.targetId() == null ? null
                : endpoints.find(migration.targetId()).map(e -> e.kind().name()).orElse(null));
        return "migration";
    }

    @PostMapping("/migrations/{id}/plan")
    String plan(@PathVariable long id, RedirectAttributes redirect) {
        return start(id, redirect, jobs::startPlan);
    }

    @PostMapping("/migrations/{id}/dry-run")
    String dryRun(@PathVariable long id, RedirectAttributes redirect) {
        return start(id, redirect, jobs::startDryRun);
    }

    @PostMapping("/migrations/{id}/run")
    String run(@PathVariable long id, RedirectAttributes redirect) {
        return start(id, redirect, jobs::startRun);
    }

    /** New options: the dry run no longer describes the run, so the migration goes back to the plan. */
    @PostMapping("/migrations/{id}/renumber")
    String renumber(@PathVariable long id, @RequestParam(defaultValue = "false") boolean renumber,
                    @RequestParam(defaultValue = "ABOVE_HIGHEST") RenumberOptions.Allocation renumberAllocation,
                    @RequestParam(defaultValue = "false") boolean renumberLists,
                    @RequestParam(defaultValue = "false") boolean renumberIncludeNewer, RedirectAttributes redirect) {
        Migration migration = find(id);
        if (migration.isSplit() || migration.state().busy() || migration.state() == Migration.State.RUN_DONE) {
            redirect.addFlashAttribute("error", "The options can be changed on a merge that is not working and has not been run");
            return "redirect:/migrations/" + id;
        }
        RenumberOptions chosen = new RenumberOptions(renumber, renumberAllocation, renumberLists, renumberIncludeNewer, Set.of());
        migrations.renumberChanged(id, chosen.enabled() ? Json.write(chosen) : null);
        audit.record("migration_renumber", "#" + id + (chosen.enabled() ? " on" : " off"));
        redirect.addFlashAttribute("message", migration.dryRunId() == null ? "Saved."
                : "Saved. The dry run was made with the old options, so run it again.");
        return "redirect:/migrations/" + id;
    }

    /** The properties the person ticked on the dry-run report: they are left as they are. The dry run stays valid. */
    @PostMapping("/migrations/{id}/renumber/excluded")
    String excluded(@PathVariable long id, @RequestParam(required = false) List<String> excluded, RedirectAttributes redirect) {
        Migration migration = find(id);
        RenumberOptions current = migration.renumber();
        if (migration.isSplit() || !current.enabled() || migration.state().busy() || migration.state() == Migration.State.RUN_DONE) {
            redirect.addFlashAttribute("error", "The properties can be chosen after a dry run of a merge that renumbers, before it is run");
            return "redirect:/migrations/" + id;
        }
        Set<String> chosen = new java.util.TreeSet<>();
        if (excluded != null) {
            excluded.stream().map(String::strip).filter(name -> !name.isEmpty()).forEach(chosen::add);
        }
        migrations.excludedChanged(id, Json.write(new RenumberOptions(current.enabled(), current.allocation(), current.lists(),
                current.includeNewerNodes(), chosen)));
        audit.record("migration_renumber_excluded", "#" + id + " " + chosen.size() + " properties");
        redirect.addFlashAttribute("message", chosen.isEmpty() ? "Every property that holds an old id will be changed."
                : chosen.size() + " properties will be left as they are.");
        return "redirect:/dry-runs/" + migration.dryRunId();
    }

    @PostMapping("/migrations/{id}/rollback")
    String rollback(@PathVariable long id, RedirectAttributes redirect) {
        try {
            jobs.rollback(find(id));
            redirect.addFlashAttribute("message", "Rolled back. The old database is back in the data directory.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/migrations/" + id;
    }

    @PostMapping("/migrations/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes redirect) {
        Migration migration = find(id);
        if (migration.state().busy()) {
            redirect.addFlashAttribute("error", "It is working right now. Wait until it stops.");
            return "redirect:/migrations/" + id;
        }
        migrations.delete(id);
        jobLog.deleteScope(migration.scope());
        audit.record("migration_delete", "#" + id + " " + migration.name());
        redirect.addFlashAttribute("message", "Deleted '" + migration.name() + "'. Its dry-run reports are kept.");
        return "redirect:/migrations";
    }

    @GetMapping("/dry-runs/{id}")
    String report(@PathVariable long id, Model model) {
        ReportStore.Row row = reports.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("row", row);
        if (row.status() == ReportStore.Status.DONE) {
            DryRunReport report = reports.reportJson(id).map(j -> Json.read(j, DryRunReport.class)).orElse(null);
            model.addAttribute("report", report);
            // the properties can be chosen only on the report that the migration will run with
            Migration migration = row.migrationId() == null ? null : migrations.find(row.migrationId()).orElse(null);
            boolean current = migration != null && migration.dryRunId() != null && migration.dryRunId() == id
                    && !migration.state().busy() && migration.state() != Migration.State.RUN_DONE;
            model.addAttribute("canChoose", current && migration.renumber().enabled());
            model.addAttribute("excludedNow", migration == null ? Set.of() : migration.renumber().excluded());
            model.addAttribute("properties", report == null ? List.of()
                    : RenumberView.properties(report.renumbering(), migration != null && current ? migration.renumber().excluded() : Set.of()));
        } else if (row.status() == ReportStore.Status.FAILED) {
            // the step is known only while the migration's latest run is this dry run
            String step = null;
            if (row.migrationId() != null) {
                String scope = "migration:" + row.migrationId();
                long start = jobLog.lastRunStart(scope);
                if ("Dry run started".equals(jobLog.messageAt(start))) {
                    step = jobLog.lastStep(scope, start);
                }
            }
            model.addAttribute("failure", Failure.explain(row.error(), Failure.Stage.DRY_RUN, step));
        }
        return "dry-run";
    }

    @GetMapping(value = "/dry-runs/{id}/id-mapping.csv", produces = "text/csv")
    ResponseEntity<String> idMapping(@PathVariable long id) {
        DryRunReport report = reports.reportJson(id).map(j -> Json.read(j, DryRunReport.class))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (report.renumbering() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=new-ids-" + id + ".csv")
                .body(RenumberView.mappingCsv(report.renumbering()));
    }

    @GetMapping(value = "/dry-runs/{id}/report.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> json(@PathVariable long id) {
        return reports.reportJson(id)
                .map(j -> ResponseEntity.ok().header("Content-Disposition", "attachment; filename=dry-run-" + id + ".json").body(j))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String start(long id, RedirectAttributes redirect, java.util.function.Consumer<Migration> job) {
        try {
            job.accept(find(id));
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/migrations/" + id;
    }

    private void fillForm(Model model, String name, Long target, SelectionForm selection) {
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("name", name);
        model.addAttribute("targetId", target);
        model.addAttribute("selection", selection);
        model.addAttribute("defaultOrgProperty", settings.get().orgProperty());
    }

    private Migration find(long id) {
        return migrations.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
