package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.util.List;

/** What a migration would do, worked out before anything is compared in detail. Stored with the migration as JSON. */
public record MigrationPlan(
        String createdAt,
        String mode,
        Side from,
        Side to,
        SelectionConfig selection,
        boolean selectsEverything,
        long selectedNodes,
        long estimatedDryRunSeconds,
        List<Step> steps,
        List<String> warnings) {

    public record Side(String name, String kind, String serverVersion, String database, long nodes, long relationships,
                       int indexes, int constraints) {
    }

    /** {@code available} is false for steps this version of the tool cannot run yet. */
    public record Step(int number, String title, String detail, boolean available) {
    }
}
