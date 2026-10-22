package com.etolv.neo4jtool.web;

import java.util.ArrayList;
import java.util.List;

/** What one run of a job has reported so far: the steps it began, with when, and where the import is. */
final class JobTimeline {

    enum Stage { PLAN, DRY_RUN, RUN }

    private final Stage stage;
    private final ImportWatch importWatch = new ImportWatch();
    private final List<RunSteps.Mark> marks = new ArrayList<>();
    private volatile long endedAt;

    JobTimeline(Stage stage) {
        this.stage = stage;
    }

    Stage stage() {
        return stage;
    }

    ImportWatch importWatch() {
        return importWatch;
    }

    /** Notes when the job stopped (it failed), so the time of its last step stops growing. */
    void end() {
        endedAt = System.currentTimeMillis();
    }

    long endedAt() {
        return endedAt;
    }

    synchronized void mark(String phase) {
        marks.add(new RunSteps.Mark(phase, System.currentTimeMillis()));
    }

    synchronized List<RunSteps.Mark> marks() {
        return List.copyOf(marks);
    }
}
