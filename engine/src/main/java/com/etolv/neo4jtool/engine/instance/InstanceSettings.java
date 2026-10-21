package com.etolv.neo4jtool.engine.instance;

import java.nio.file.Path;
import java.time.Duration;

/**
 * How to start a throw-away Neo4j server on a dump or a stopped data directory.
 *
 * @param runner      DOCKER runs a Neo4j image, LOCAL runs a Neo4j installation on this machine
 * @param dockerImage image used by the DOCKER runner; use the version that wrote the dump
 * @param neo4jHome   Neo4j installation used by the LOCAL runner
 * @param workDir     where temporary databases are created; each one is removed when it is closed
 */
public record InstanceSettings(
        Runner runner,
        String dockerImage,
        Path neo4jHome,
        Path workDir,
        String heapSize,
        String pageCacheSize,
        Duration startTimeout) {

    public enum Runner { DOCKER, LOCAL }

    public InstanceSettings {
        if (runner == null) {
            throw new IllegalArgumentException("runner is required");
        }
        if (workDir == null) {
            throw new IllegalArgumentException("workDir is required");
        }
        if (runner == Runner.DOCKER && (dockerImage == null || dockerImage.isBlank())) {
            throw new IllegalArgumentException("A Docker image is required for the Docker runner");
        }
        if (runner == Runner.LOCAL && neo4jHome == null) {
            throw new IllegalArgumentException("The Neo4j installation directory is required for the local runner");
        }
        heapSize = heapSize == null || heapSize.isBlank() ? "1g" : heapSize;
        pageCacheSize = pageCacheSize == null || pageCacheSize.isBlank() ? "1g" : pageCacheSize;
        startTimeout = startTimeout == null ? Duration.ofMinutes(10) : startTimeout;
    }
}
