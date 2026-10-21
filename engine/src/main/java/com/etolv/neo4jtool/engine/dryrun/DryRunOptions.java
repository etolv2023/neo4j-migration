package com.etolv.neo4jtool.engine.dryrun;

/**
 * @param batchSize            how many node ids are looked up per query on the target
 * @param sampleSize           how many example rows each report section keeps
 * @param includeRelationships whether relationships of the affected nodes are analysed
 */
public record DryRunOptions(int batchSize, int sampleSize, boolean includeRelationships) {

    public DryRunOptions {
        if (batchSize < 100) {
            throw new IllegalArgumentException("batchSize must be at least 100");
        }
        if (sampleSize < 0) {
            throw new IllegalArgumentException("sampleSize must not be negative");
        }
    }

    public static DryRunOptions defaults() {
        return new DryRunOptions(10_000, 20, true);
    }
}
