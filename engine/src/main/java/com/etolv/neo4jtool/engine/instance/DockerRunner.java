package com.etolv.neo4jtool.engine.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs the Neo4j image of the settings. {@code neo4j-admin} is started with its own entrypoint on purpose: the image's
 * default entrypoint changes the owner of everything under /data, which must never happen to a real data directory.
 */
public final class DockerRunner implements InstanceRunner {

    private final InstanceSettings settings;

    public DockerRunner(InstanceSettings settings) {
        this.settings = settings;
    }

    @Override
    public Path dumpStoppedDatabase(Path dataDirectory, String database, Path outputDirectory, Consumer<String> log) {
        if (!Files.isDirectory(dataDirectory.resolve("databases").resolve(database))) {
            throw new InstanceException("There is no database '" + database + "' in " + dataDirectory
                    + ". Give the data directory (the one that contains databases/ and transactions/).");
        }
        // Run as the owner of the data directory so that nothing there changes owner and the store lock can be taken.
        String owner = owner(dataDirectory);
        Commands.run(List.of("docker", "run", "--rm", "--entrypoint", "neo4j-admin", "--user", owner,
                "-v", dataDirectory.toAbsolutePath() + ":/data", "-v", outputDirectory.toAbsolutePath() + ":/dump",
                settings.dockerImage(), "database", "dump", "--to-path=/dump", database), Map.of(), null, log);
        Path dump = outputDirectory.resolve(database + ".dump");
        if (!Files.isRegularFile(dump)) {
            throw new InstanceException("neo4j-admin finished but " + dump + " was not written");
        }
        return dump;
    }

    @Override
    public RunningInstance startFromDump(Path dumpFile, String database, Consumer<String> log) {
        Workspace workspace = Workspace.create(settings.workDir(), "dump");
        try {
            Path data = workspace.directory("data");
            log.accept("Loading " + dumpFile + " as database '" + database + "'");
            Commands.run(List.of("docker", "run", "--rm", "-i", "--entrypoint", "neo4j-admin",
                    "-v", data.toAbsolutePath() + ":/data", settings.dockerImage(),
                    "database", "load", "--from-stdin", "--overwrite-destination=true", database), Map.of(), dumpFile, log);
            return launch(data, database, log, workspace, false);
        } catch (RuntimeException e) {
            workspace.delete(root -> removeFiles(root));
            throw e;
        }
    }

    @Override
    public RunningInstance serveDataDirectory(Path dataDirectory, String database, Consumer<String> log) {
        return launch(dataDirectory, database, log, null, true);
    }

    @Override
    public void importDatabase(Path dataDirectory, String database, ImportFiles files, Consumer<String> log) {
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--entrypoint", "neo4j-admin",
                "-v", dataDirectory.toAbsolutePath() + ":/data", "-v", files.directory().toAbsolutePath() + ":/import:ro",
                settings.dockerImage()));
        command.addAll(ImportFiles.arguments(database, "/import/", "/tmp/import.report", files));
        Commands.run(command, Map.of(), null, log);
    }

    @Override
    public void checkDatabase(Path dataDirectory, String database, Consumer<String> log) {
        Commands.run(List.of("docker", "run", "--rm", "--entrypoint", "neo4j-admin", "--user", owner(dataDirectory),
                "-v", dataDirectory.toAbsolutePath() + ":/data", settings.dockerImage(),
                "database", "check", "--report-path=/tmp", database), Map.of(), null, log);
    }

    /**
     * Starts a server on {@code data}. With {@code graceful} the server is stopped with time to shut down cleanly, which
     * a database that will be dumped or checked afterwards needs; throw-away servers are simply removed.
     *
     * @param workspace deleted together with the server, or null when the caller owns the data
     */
    private RunningInstance launch(Path data, String database, Consumer<String> log, Workspace workspace, boolean graceful) {
        String name = "neo4j-migration-" + UUID.randomUUID().toString().substring(0, 8);
        boolean started = false;
        try {
            List<String> run = new ArrayList<>(List.of("docker", "run", "-d", "--name", name, "-e", "NEO4J_AUTH=none",
                    "-e", "NEO4J_initial_dbms_default__database=" + database,
                    "-e", "NEO4J_server_memory_heap_initial__size=" + settings.heapSize(),
                    "-e", "NEO4J_server_memory_heap_max__size=" + settings.heapSize(),
                    "-e", "NEO4J_server_memory_pagecache_size=" + settings.pageCacheSize(),
                    "-p", "127.0.0.1::7687", "-v", data.toAbsolutePath() + ":/data", settings.dockerImage()));
            Commands.run(run, Map.of(), null, log);
            started = true;
            String uri = "bolt://127.0.0.1:" + hostPort(name);
            log.accept("Waiting for " + uri);
            BoltWait.until(uri, settings.startTimeout(), () -> died(name));
            log.accept("Temporary Neo4j is ready on " + uri);
            return new Instance(uri, () -> remove(name, workspace, graceful));
        } catch (RuntimeException e) {
            if (started) {
                log.accept(logsOf(name));
            }
            remove(name, workspace, false);
            throw e;
        }
    }

    private void remove(String container, Workspace workspace, boolean graceful) {
        try {
            if (graceful) {
                Commands.output(List.of("docker", "stop", "-t", "600", container));
            }
            Commands.output(List.of("docker", "rm", "-f", container));
        } catch (InstanceException ignored) {
            // never started
        }
        if (workspace != null) {
            workspace.delete(root -> removeFiles(root));
        }
    }

    @Override
    public void deleteWorkspace(Workspace workspace) {
        workspace.delete(this::removeFiles);
    }

    /** Files written by a container can belong to another user; a container removes them. */
    private void removeFiles(Path root) {
        Commands.output(List.of("docker", "run", "--rm", "--entrypoint", "sh",
                "-v", root.getParent().toAbsolutePath() + ":/w", settings.dockerImage(),
                "-c", "rm -rf /w/" + root.getFileName()));
    }

    private static String hostPort(String container) {
        String mapping = Commands.output(List.of("docker", "port", container, "7687/tcp")).lines().findFirst().orElse("");
        int colon = mapping.lastIndexOf(':');
        if (colon < 0) {
            throw new InstanceException("Docker did not report the port of " + container + ": '" + mapping + "'");
        }
        return mapping.substring(colon + 1).trim();
    }

    private static String died(String container) {
        try {
            String running = Commands.output(List.of("docker", "inspect", "-f", "{{.State.Running}}", container));
            return running.equals("true") ? null : logsOf(container);
        } catch (InstanceException e) {
            return e.getMessage();
        }
    }

    private static String logsOf(String container) {
        try {
            return Commands.output(List.of("docker", "logs", "--tail", "30", container));
        } catch (InstanceException e) {
            return e.getMessage();
        }
    }

    private static String owner(Path path) {
        try {
            return Files.getAttribute(path, "unix:uid") + ":" + Files.getAttribute(path, "unix:gid");
        } catch (IOException | UnsupportedOperationException e) {
            throw new InstanceException("Could not read the owner of " + path + ": " + e.getMessage(), e);
        }
    }

    private record Instance(String boltUri, Runnable onClose) implements RunningInstance {
        @Override
        public void close() {
            onClose.run();
        }
    }
}
