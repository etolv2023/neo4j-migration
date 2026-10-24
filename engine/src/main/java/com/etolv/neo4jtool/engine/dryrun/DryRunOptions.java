package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.build.RenumberOptions;

/**
 * @param batchSize            how many node ids are looked up per query on the target
 * @param sampleSize           how many example rows each report section keeps
 * @param includeRelationships whether relationships of the affected nodes are analysed
 * @param renumber             what the run will do with selected nodes whose id the target uses for another node; when it is
 *                             on, the report lists the new ids and the properties that hold the old ones
 * @param workers              threads that read nodes when the properties are searched
 */
public record DryRunOptions(int batchSize, int sampleSize, boolean includeRelationships, RenumberOptions renumber, int workers) {

    public DryRunOptions {
        if (batchSize < 100) {
            throw new IllegalArgumentException("batchSize must be at least 100");
        }
        if (sampleSize < 0) {
            throw new IllegalArgumentException("sampleSize must not be negative");
        }
        if (workers < 1 || workers > 256) {
            throw new IllegalArgumentException("workers must be between 1 and 256");
        }
        renumber = renumber == null ? RenumberOptions.off() : renumber;
    }

    public DryRunOptions(int batchSize, int sampleSize, boolean includeRelationships) {
        this(batchSize, sampleSize, includeRelationships, RenumberOptions.off(), 1);
    }

    public static DryRunOptions defaults() {
        return new DryRunOptions(10_000, 20, true);
    }

    public DryRunOptions withRenumber(RenumberOptions renumber, int workers) {
        return new DryRunOptions(batchSize, sampleSize, includeRelationships, renumber, workers);
    }
}
