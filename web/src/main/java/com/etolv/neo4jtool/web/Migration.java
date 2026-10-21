package com.etolv.neo4jtool.web;

import java.time.Instant;

/**
 * One pass through the flow: from, to, selection, plan, dry run. {@code targetId} is null when the data is split into a new database.
 */
public record Migration(long id, String name, long sourceId, Long targetId, String sourceName, String targetName,
                        String selectionYaml, State state, String planJson, Long dryRunId, String lastError,
                        Instant createdAt, Instant updatedAt) {

    public enum State {
        DRAFT("Draft"), PLANNING("Planning"), PLANNED("Planned"), DRY_RUNNING("Dry run running"), DRY_RUN_DONE("Dry run done");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean busy() {
            return this == PLANNING || this == DRY_RUNNING;
        }
    }

    public boolean isSplit() {
        return targetId == null;
    }

    public String scope() {
        return "migration:" + id;
    }
}
