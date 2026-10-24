package com.etolv.neo4jtool.engine.build;

/**
 * @param build    how the new database is made
 * @param swap     for a stopped data directory: put the new database in its place (the old one is kept). Without it the
 *                 merged database is only written as a dump file.
 * @param renumber what to do with a selected node whose id the target uses for another node
 */
public record MergeOptions(BuildOptions build, boolean swap, RenumberOptions renumber) {

    public MergeOptions(BuildOptions build, boolean swap) {
        this(build, swap, RenumberOptions.off());
    }
}
