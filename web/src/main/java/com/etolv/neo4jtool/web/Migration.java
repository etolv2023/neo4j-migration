package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.build.RenumberOptions;
import java.time.Instant;

/**
 * One pass through the flow: from, to, selection, plan, dry run. {@code targetId} is null when the data is split into a new database.
 */
public record Migration(long id, String name, long sourceId, Long targetId, String sourceName, String targetName,
                        String selectionYaml, State state, String planJson, Long dryRunId, String runJson, String lastError,
                        Instant createdAt, Instant updatedAt, String renumberJson) {

    public enum State {
        DRAFT("Draft"), PLANNING("Planning"), PLANNED("Planned"), DRY_RUNNING("Dry run running"), DRY_RUN_DONE("Dry run done"),
        RUNNING("Run running"), RUN_DONE("Run done");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean busy() {
            return this == PLANNING || this == DRY_RUNNING || this == RUNNING;
        }
    }

    /** What a merge does with a selected node whose id is taken on the target; off when nothing was chosen. */
    public RenumberOptions renumber() {
        return renumberJson == null || renumberJson.isBlank() ? RenumberOptions.off() : Json.read(renumberJson, RenumberOptions.class);
    }

    public boolean isSplit() {
        return targetId == null;
    }

    public String scope() {
        return "migration:" + id;
    }
}
