package com.etolv.neo4jtool.engine.build;

import java.util.List;

/**
 * What a finished merge produced.
 *
 * @param targetNodes             nodes the target already had; all of them are in the new database with their ids
 * @param restoredNodes           selected nodes that were missing on the target and were put in
 * @param alreadyThereNodes       selected nodes that the target already had, as the same node
 * @param targetRelationships     relationships the target already had
 * @param restoredRelationships   relationships of the restored nodes that were put in
 * @param droppedRelationships    relationships of the restored nodes whose other end is not on the target (or is another
 *                                node there), which were left out
 * @param backupFile              the dump of the target taken before anything was built
 * @param outputFile              the merged dump, when the merge was not swapped in (otherwise null)
 * @param swapped                 the new database replaced the old one in the data directory
 * @param dataDirectory           the data directory that was swapped (null when not swapped)
 * @param keepName                the folder in the data directory that holds the old database (null when not swapped)
 * @param rolledBackAt            when the swap was undone, otherwise null
 */
public record MergeResult(
        String startedAt,
        String finishedAt,
        String database,
        long targetNodes,
        long restoredNodes,
        long alreadyThereNodes,
        long targetRelationships,
        long restoredRelationships,
        long droppedRelationships,
        long propertiesWrittenAfter,
        int schemaStatements,
        boolean consistencyChecked,
        String backupFile,
        long backupBytes,
        String outputFile,
        long outputBytes,
        boolean swapped,
        String dataDirectory,
        String keepName,
        String rolledBackAt,
        List<Phase> phases) {

    public MergeResult withRolledBackAt(String time) {
        return new MergeResult(startedAt, finishedAt, database, targetNodes, restoredNodes, alreadyThereNodes, targetRelationships,
                restoredRelationships, droppedRelationships, propertiesWrittenAfter, schemaStatements, consistencyChecked,
                backupFile, backupBytes, outputFile, outputBytes, swapped, dataDirectory, keepName, time, phases);
    }
}
