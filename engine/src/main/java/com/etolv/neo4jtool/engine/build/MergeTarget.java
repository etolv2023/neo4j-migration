package com.etolv.neo4jtool.engine.build;

import java.nio.file.Path;

/**
 * The database a merge puts the restored nodes into. It is always something that is not being served: the data directory
 * of a stopped server (the result can be swapped in) or a dump file (the result is a new dump file).
 */
public record MergeTarget(Path path, String database, boolean dataDirectory) {

    public static MergeTarget dataDirectory(Path path, String database) {
        return new MergeTarget(path, database, true);
    }

    public static MergeTarget dump(Path path, String database) {
        return new MergeTarget(path, database, false);
    }
}
