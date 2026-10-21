package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.instance.InstanceSettings;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything the person can change on the settings page.
 *
 * @param runner       how dumps and stopped data directories are served: DOCKER or LOCAL
 * @param dockerImage  Neo4j image for DOCKER; use the version that wrote the dumps
 * @param neo4jHome    Neo4j installation directory for LOCAL
 * @param workDir      where temporary databases are made; needs room for a copy of each dump that is loaded
 * @param outputDir    where the dump files that a split writes are put
 * @param backupDir    where a merge keeps the dump of the stopped target that it takes before building anything
 * @param mergeSwap    a merge into a stopped data directory puts the new database in its place (the old one is kept);
 *                     when off, the merged database is only written as a dump file
 * @param includeNeighbours the default for new migrations: also move nodes that have no organisation but are connected to
 *                     a selected node (the lines of an invoice, say); one step only
 * @param copySchema   a split recreates the source's indexes and constraints in the new database
 * @param checkAfterBuild a split runs the store consistency check on the new database before it dumps it
 */
public record AppSettings(
        InstanceSettings.Runner runner,
        String dockerImage,
        String neo4jHome,
        String workDir,
        String outputDir,
        String backupDir,
        boolean mergeSwap,
        boolean copySchema,
        boolean checkAfterBuild,
        String tempHeap,
        String tempPageCache,
        int startTimeoutMinutes,
        String orgProperty,
        boolean includeNeighbours,
        int batchSize,
        int sampleSize,
        boolean analyseRelationships) {

    public static AppSettings defaults() {
        return new AppSettings(InstanceSettings.Runner.DOCKER, "neo4j:5.26.29-community", "",
                Path.of(System.getProperty("java.io.tmpdir"), "neo4j-migration").toString(),
                Path.of(System.getProperty("java.io.tmpdir"), "neo4j-migration-output").toString(),
                Path.of(System.getProperty("java.io.tmpdir"), "neo4j-migration-backups").toString(), true, true, true,
                "1g", "1g", 15, "org_id", true, 10_000, 20, true);
    }

    public InstanceSettings instanceSettings() {
        return new InstanceSettings(runner, dockerImage, neo4jHome == null || neo4jHome.isBlank() ? null : Path.of(neo4jHome),
                Path.of(workDir), tempHeap, tempPageCache, Duration.ofMinutes(startTimeoutMinutes));
    }
}
