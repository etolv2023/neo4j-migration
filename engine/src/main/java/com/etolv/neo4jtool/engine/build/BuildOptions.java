package com.etolv.neo4jtool.engine.build;

/**
 * @param batchSize        node ids read from the source per round trip
 * @param copySchema       recreate the indexes and constraints of the original database in the new one
 * @param checkConsistency run {@code neo4j-admin database check} on the new database (slow on a big one)
 * @param workers          threads that read the source and write the CSV files at the same time
 * @param importThreads    threads of the import (0 = neo4j-admin decides: one per core)
 * @param importMemory     memory limit of the import, for example "16g" (empty = neo4j-admin decides)
 * @param fastReaders      opens the finished database for the fast, parallel comparison with what was exported; null
 *                         compares over Bolt on the temporary server instead
 */
public record BuildOptions(int batchSize, boolean copySchema, boolean checkConsistency, int workers, int importThreads,
                           String importMemory, FastReader.Factory fastReaders) {

    public BuildOptions {
        if (batchSize < 100) {
            throw new IllegalArgumentException("batchSize must be at least 100");
        }
        if (workers < 1 || workers > 256) {
            throw new IllegalArgumentException("workers must be between 1 and 256");
        }
        if (importThreads < 0 || importThreads > 1024) {
            throw new IllegalArgumentException("importThreads must be between 0 and 1024");
        }
        importMemory = importMemory == null ? "" : importMemory.trim();
    }

    public BuildOptions(int batchSize, boolean copySchema, boolean checkConsistency) {
        this(batchSize, copySchema, checkConsistency, 1, 0, "", null);
    }

    public static BuildOptions defaults() {
        return new BuildOptions(10_000, true, true);
    }

    public BuildOptions withWorkers(int workers) {
        return new BuildOptions(batchSize, copySchema, checkConsistency, workers, importThreads, importMemory, fastReaders);
    }

    public BuildOptions withFastReaders(FastReader.Factory fastReaders) {
        return new BuildOptions(batchSize, copySchema, checkConsistency, workers, importThreads, importMemory, fastReaders);
    }
}
