package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import java.util.ArrayList;
import java.util.List;

/** Names the step in progress and records how long each step took. */
final class Phases {

    private final List<Phase> phases = new ArrayList<>();
    private final DryRunProgress progress;
    private String current;
    private long since;

    Phases(DryRunProgress progress) {
        this.progress = progress;
    }

    void start(String name) {
        finish();
        current = name;
        since = System.nanoTime();
        progress.phase(name);
    }

    void finish() {
        if (current != null) {
            phases.add(new Phase(current, (System.nanoTime() - since) / 1_000_000));
            current = null;
        }
    }

    List<Phase> all() {
        finish();
        return phases;
    }
}
