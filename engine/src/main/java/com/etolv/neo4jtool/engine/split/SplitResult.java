package com.etolv.neo4jtool.engine.split;

import java.util.List;

/**
 * What a finished split produced.
 *
 * @param relationshipsToOutside relationships that start at a selected node and end at one that is not selected; they
 *                               are not in the new database (relationships that come in from outside are not counted here)
 * @param propertiesWrittenAfter node properties the CSV import cannot carry, written afterwards with their exact type
 * @param schemaStatements       indexes and constraints recreated
 * @param nodeFingerprint        hex of the sum of the node hashes; the same sum was read back from the new database
 */
public record SplitResult(
        String startedAt,
        String finishedAt,
        String database,
        long nodes,
        long relationships,
        long relationshipsToOutside,
        long propertiesWrittenAfter,
        int schemaStatements,
        boolean consistencyChecked,
        String outputFile,
        long outputBytes,
        String nodeFingerprint,
        String relationshipFingerprint,
        List<Phase> phases) {

    public record Phase(String name, long millis) {
    }
}
