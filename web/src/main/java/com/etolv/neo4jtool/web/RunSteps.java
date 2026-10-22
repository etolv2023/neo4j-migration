package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.build.Phase;
import java.util.ArrayList;
import java.util.List;

/**
 * The steps of a job, written out, so the page can show which are done, which one is running and which are left.
 *
 * <p>The engine reports each step by name as it starts it. A step here has the start of those names to look for, so the
 * list follows what really happens: a name that matches no step (the passes of an export, say) is shown as the detail of
 * the step it belongs to, and the steps that have not started yet come after the ones that have, in their usual order.
 */
final class RunSteps {

    enum State { DONE, RUNNING, WAITING, FAILED }

    /** @param seconds how long it took or has taken so far, -1 when it is not known; @param percent -1 when not known */
    record Step(String title, State state, long seconds, String detail, int percent) {

        Step with(State newState, String newDetail, int newPercent) {
            return new Step(title, newState, seconds, newDetail, newPercent);
        }
    }

    record Mark(String phase, long at) {
    }

    record Def(String title, boolean optional, List<String> prefixes) {

        boolean matches(String phase) {
            return prefixes.stream().anyMatch(phase::startsWith);
        }
    }

    /** What the steps depend on: the names of the databases and the settings. {@code target} is null for a split. */
    record Context(String source, String target, boolean swap, boolean directoryOutput, boolean check, boolean analyse) {
    }

    private RunSteps() {
    }

    static List<Def> plan(Context c) {
        List<Def> steps = new ArrayList<>();
        steps.add(new Def("Open the source", false, List.of(opening(c.source()))));
        if (c.target() != null) {
            steps.add(new Def("Open the target", false, List.of(opening(c.target()))));
        }
        steps.add(new Def("Count what is in the databases", false, List.of("Counting")));
        return steps;
    }

    static List<Def> dryRun(Context c) {
        List<Def> steps = new ArrayList<>();
        steps.add(new Def("Open the source", false, List.of(opening(c.source()))));
        if (c.target() != null) {
            steps.add(new Def("Open the target", false, List.of(opening(c.target()))));
        }
        steps.add(new Def("Read the selected nodes", false, List.of("Reading the selected")));
        if (c.target() != null) {
            steps.add(new Def("Compare them with the target", false, List.of("Comparing with the target")));
        }
        steps.add(new Def("Analyse the relationships", !c.analyse(), List.of("Analysing relationships")));
        return steps;
    }

    static List<Def> merge(Context c) {
        List<Def> steps = new ArrayList<>();
        steps.add(new Def("Open the databases", false, List.of(opening(c.source()), opening(c.target()))));
        steps.add(new Def("Back up the target", false, List.of("Backing up the target")));
        steps.add(new Def("Load the backup", true, List.of("Loading the backup")));
        steps.add(new Def("Compare the selection with the target", false, List.of("Comparing the selection")));
        steps.add(new Def("Export the target to CSV", false, List.of("Exporting the target")));
        steps.add(new Def("Export the nodes to restore", false, List.of("Exporting the nodes to restore")));
        addBuildSteps(steps, c);
        steps.add(c.swap()
                ? new Def("Swap the new database into the data directory", false, List.of("Swapping the new database"))
                : new Def("Write the merged dump", false, List.of("Writing the merged dump")));
        return steps;
    }

    static List<Def> split(Context c) {
        List<Def> steps = new ArrayList<>();
        steps.add(new Def("Open the source", false, List.of(opening(c.source()))));
        steps.add(new Def("Read the selected ids", false, List.of("Reading the selected ids")));
        steps.add(new Def("Export the selection to CSV", false, List.of("Exporting the selection")));
        addBuildSteps(steps, c);
        steps.add(c.directoryOutput()
                ? new Def("Move the data directory into place", false, List.of("Moving the data directory"))
                : new Def("Write the dump", false, List.of("Writing the dump")));
        return steps;
    }

    /** The steps both a split and a merge go through to make the new database. */
    private static void addBuildSteps(List<Def> steps, Context c) {
        steps.add(new Def("Build the new database (neo4j-admin import)", false, List.of("Building the new database")));
        steps.add(new Def("Finish it on a temporary server", false, List.of("Finishing the new database")));
        steps.add(new Def("Compare it with the exported data", false, List.of("Comparing the new database")));
        steps.add(new Def("Stop the temporary server", false, List.of("Stopping the temporary server")));
        steps.add(new Def("Check the store", !c.check(), List.of("Checking the store")));
    }

    private static String opening(String name) {
        return "Opening '" + name + "'";
    }

    /**
     * The steps of a job that is working: the ones reported so far in the order they began (the last one is running, or
     * failed when {@code failed}), then the ones still to come.
     */
    static List<Step> of(List<Def> defs, List<Mark> marks, long now, boolean failed) {
        return build(defs, marks, now, failed ? State.FAILED : State.RUNNING, false);
    }

    /** The steps of a job that finished, from how long each step took; steps it did not report count as done. */
    static List<Step> finished(List<Def> defs, List<Phase> phases) {
        List<Mark> marks = new ArrayList<>();
        long at = 0;
        for (Phase phase : phases) {
            marks.add(new Mark(phase.name(), at));
            at += phase.millis();
        }
        return build(defs, marks, at, State.DONE, true);
    }

    /** All the steps of a stage that is done (no time known) or that has not begun. */
    static List<Step> all(List<Def> defs, boolean done) {
        List<Step> steps = new ArrayList<>();
        for (Def def : defs) {
            if (!def.optional()) {
                steps.add(new Step(def.title(), done ? State.DONE : State.WAITING, -1, null, -1));
            }
        }
        return steps;
    }

    private static List<Step> build(List<Def> defs, List<Mark> marks, long now, State lastState, boolean complete) {
        int n = defs.size();
        long[] start = new long[n];
        long[] end = new long[n];
        String[] detail = new String[n];
        java.util.Arrays.fill(start, -1);
        java.util.Arrays.fill(end, -1);
        List<Integer> order = new ArrayList<>();
        int last = -1;
        for (Mark mark : marks) {
            int index = indexOf(defs, mark.phase());
            if (index < 0) {
                if (last >= 0) {
                    detail[last] = mark.phase();
                }
                continue;
            }
            if (start[index] >= 0) {
                continue;
            }
            if (last >= 0 && end[last] < 0) {
                end[last] = mark.at();
            }
            start[index] = mark.at();
            order.add(index);
            last = index;
        }
        List<Step> steps = new ArrayList<>();
        for (int index : order) {
            boolean current = index == last && !complete;
            State state = current ? lastState : State.DONE;
            long finish = end[index] >= 0 ? end[index] : now;
            steps.add(new Step(defs.get(index).title(), state, Math.max(0, (finish - start[index]) / 1000),
                    current ? detail[index] : null, -1));
        }
        for (int index = 0; index < n; index++) {
            if (start[index] < 0 && !defs.get(index).optional()) {
                steps.add(new Step(defs.get(index).title(), complete ? State.DONE : State.WAITING, -1, null, -1));
            }
        }
        return steps;
    }

    private static int indexOf(List<Def> defs, String phase) {
        for (int i = 0; i < defs.size(); i++) {
            if (defs.get(i).matches(phase)) {
                return i;
            }
        }
        return -1;
    }
}
