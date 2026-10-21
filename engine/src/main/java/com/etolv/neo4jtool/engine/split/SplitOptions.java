package com.etolv.neo4jtool.engine.split;

/**
 * @param batchSize        node ids read from the source per query
 * @param copySchema       recreate the source's indexes and constraints in the new database
 * @param checkConsistency run {@code neo4j-admin database check} on the new database (slow on a big one)
 */
public record SplitOptions(int batchSize, boolean copySchema, boolean checkConsistency) {

    public SplitOptions {
        if (batchSize < 100) {
            throw new IllegalArgumentException("batchSize must be at least 100");
        }
    }

    public static SplitOptions defaults() {
        return new SplitOptions(10_000, true, true);
    }
}
