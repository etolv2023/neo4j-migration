package com.etolv.neo4jtool.engine.build;

/**
 * @param build how the new database is made
 * @param swap  for a stopped data directory: put the new database in its place (the old one is kept). Without it the
 *              merged database is only written as a dump file.
 */
public record MergeOptions(BuildOptions build, boolean swap) {
}
