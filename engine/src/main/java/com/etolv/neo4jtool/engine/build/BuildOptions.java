package com.etolv.neo4jtool.engine.build;

/**
 * @param batchSize        node ids read from the source per query
 * @param copySchema       recreate the indexes and constraints of the original database in the new one
 * @param checkConsistency run {@code neo4j-admin database check} on the new database (slow on a big one)
 */
public record BuildOptions(int batchSize, boolean copySchema, boolean checkConsistency) {

    public BuildOptions {
        if (batchSize < 100) {
            throw new IllegalArgumentException("batchSize must be at least 100");
        }
    }

    public static BuildOptions defaults() {
        return new BuildOptions(10_000, true, true);
    }
}
