package com.etolv.neo4jtool.engine.dryrun;

/** Lets a caller (CLI, dashboard) follow a long dry run. */
public interface DryRunProgress {

    DryRunProgress NONE = new DryRunProgress() {
        @Override
        public void phase(String name) {
        }
    };

    void phase(String name);

    /** @param total -1 when the total is not known yet */
    default void progress(long done, long total) {
    }
}
