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
 * @param fastReader   read a dump or a stopped data directory directly with the Neo4j kernel on several threads, instead
 *                     of through a temporary server over Bolt (about ten times faster; falls back by itself when it cannot)
 * @param readerLib    directory with the jars of the fast reader; empty = look next to the application
 * @param readerHeap   heap of the fast reader process
 * @param readerPageCache page cache of the fast reader process; the more of the store it holds the faster the scan
 * @param workers      threads that read and write CSV at the same time
 * @param importThreads threads of the import (0 = one per core)
 * @param importMemory memory limit of the import, for example 16g (empty = sized from the memory of this machine)
 * @param idSetMemoryMb memory the selected ids may take in the application before they go to a file on disk
 * @param splitAsDirectory a split leaves a data directory instead of a dump file (no dump to write, no dump to load again)
 * @param maxJobs      migrations that may run at the same time
 * @param requireTwoFactor every user must sign in with a second factor; a user without one is sent to set it up after the password
 * @param sessionHours how long a sign-in stays valid without any activity
 * @param liveSeconds  how often a page that shows a running job, and the resource bar, ask for news (seconds)
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
        boolean analyseRelationships,
        boolean fastReader,
        String readerLib,
        String readerHeap,
        String readerPageCache,
        int workers,
        int importThreads,
        String importMemory,
        int idSetMemoryMb,
        int maxJobs,
        boolean splitAsDirectory,
        boolean requireTwoFactor,
        int sessionHours,
        int liveSeconds) {

    /** The settings as they were before the live refresh could be set: every 2 seconds. */
    public AppSettings(InstanceSettings.Runner runner, String dockerImage, String neo4jHome, String workDir, String outputDir,
                       String backupDir, boolean mergeSwap, boolean copySchema, boolean checkAfterBuild, String tempHeap,
                       String tempPageCache, int startTimeoutMinutes, String orgProperty, boolean includeNeighbours, int batchSize,
                       int sampleSize, boolean analyseRelationships, boolean fastReader, String readerLib, String readerHeap,
                       String readerPageCache, int workers, int importThreads, String importMemory, int idSetMemoryMb, int maxJobs,
                       boolean splitAsDirectory, boolean requireTwoFactor, int sessionHours) {
        this(runner, dockerImage, neo4jHome, workDir, outputDir, backupDir, mergeSwap, copySchema, checkAfterBuild, tempHeap,
                tempPageCache, startTimeoutMinutes, orgProperty, includeNeighbours, batchSize, sampleSize, analyseRelationships,
                fastReader, readerLib, readerHeap, readerPageCache, workers, importThreads, importMemory, idSetMemoryMb, maxJobs,
                splitAsDirectory, requireTwoFactor, sessionHours, 2);
    }

    /** The settings as they were before the length of a sign-in could be set: 12 hours. */
    public AppSettings(InstanceSettings.Runner runner, String dockerImage, String neo4jHome, String workDir, String outputDir,
                       String backupDir, boolean mergeSwap, boolean copySchema, boolean checkAfterBuild, String tempHeap,
                       String tempPageCache, int startTimeoutMinutes, String orgProperty, boolean includeNeighbours, int batchSize,
                       int sampleSize, boolean analyseRelationships, boolean fastReader, String readerLib, String readerHeap,
                       String readerPageCache, int workers, int importThreads, String importMemory, int idSetMemoryMb, int maxJobs,
                       boolean splitAsDirectory, boolean requireTwoFactor) {
        this(runner, dockerImage, neo4jHome, workDir, outputDir, backupDir, mergeSwap, copySchema, checkAfterBuild, tempHeap,
                tempPageCache, startTimeoutMinutes, orgProperty, includeNeighbours, batchSize, sampleSize, analyseRelationships,
                fastReader, readerLib, readerHeap, readerPageCache, workers, importThreads, importMemory, idSetMemoryMb, maxJobs,
                splitAsDirectory, requireTwoFactor, 12);
    }

    /** The settings as they were before two-factor sign-in existed. */
    public AppSettings(InstanceSettings.Runner runner, String dockerImage, String neo4jHome, String workDir, String outputDir,
                       String backupDir, boolean mergeSwap, boolean copySchema, boolean checkAfterBuild, String tempHeap,
                       String tempPageCache, int startTimeoutMinutes, String orgProperty, boolean includeNeighbours, int batchSize,
                       int sampleSize, boolean analyseRelationships, boolean fastReader, String readerLib, String readerHeap,
                       String readerPageCache, int workers, int importThreads, String importMemory, int idSetMemoryMb, int maxJobs,
                       boolean splitAsDirectory) {
        this(runner, dockerImage, neo4jHome, workDir, outputDir, backupDir, mergeSwap, copySchema, checkAfterBuild, tempHeap,
                tempPageCache, startTimeoutMinutes, orgProperty, includeNeighbours, batchSize, sampleSize, analyseRelationships,
                fastReader, readerLib, readerHeap, readerPageCache, workers, importThreads, importMemory, idSetMemoryMb, maxJobs,
                splitAsDirectory, false, 12);
    }

    /** The settings as they were before the speed options existed: those get their defaults. */
    public AppSettings(InstanceSettings.Runner runner, String dockerImage, String neo4jHome, String workDir, String outputDir,
                       String backupDir, boolean mergeSwap, boolean copySchema, boolean checkAfterBuild, String tempHeap,
                       String tempPageCache, int startTimeoutMinutes, String orgProperty, boolean includeNeighbours, int batchSize,
                       int sampleSize, boolean analyseRelationships) {
        this(runner, dockerImage, neo4jHome, workDir, outputDir, backupDir, mergeSwap, copySchema, checkAfterBuild, tempHeap,
                tempPageCache, startTimeoutMinutes, orgProperty, includeNeighbours, batchSize, sampleSize, analyseRelationships,
                true, "", "2g", "2g", defaultWorkers(), 0, "", 512, 2, false, false, 12);
    }

    public static int defaultWorkers() {
        return Math.max(1, Math.min(32, Runtime.getRuntime().availableProcessors()));
    }

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
