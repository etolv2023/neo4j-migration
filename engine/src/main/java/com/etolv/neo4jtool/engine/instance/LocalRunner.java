package com.etolv.neo4jtool.engine.instance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Runs a Neo4j installation on this machine. Its own configuration and logs are never used or written. */
public final class LocalRunner implements InstanceRunner {

    private final InstanceSettings settings;

    public LocalRunner(InstanceSettings settings) {
        this.settings = settings;
        if (!Files.isRegularFile(settings.neo4jHome().resolve("bin").resolve("neo4j-admin"))) {
            throw new InstanceException("No Neo4j installation in " + settings.neo4jHome() + " (bin/neo4j-admin is missing)");
        }
    }

    @Override
    public Path dumpStoppedDatabase(Path dataDirectory, String database, Path outputDirectory, Consumer<String> log) {
        if (!Files.isDirectory(dataDirectory.resolve("databases").resolve(database))) {
            throw new InstanceException("There is no database '" + database + "' in " + dataDirectory
                    + ". Give the data directory (the one that contains databases/ and transactions/).");
        }
        Workspace workspace = Workspace.create(settings.workDir(), "config");
        try {
            Path conf = writeConfig(workspace, dataDirectory, database, 0);
            Commands.run(List.of(admin(), "database", "dump", "--to-path=" + outputDirectory.toAbsolutePath(), database),
                    environment(conf), null, log);
        } finally {
            workspace.delete(root -> { });
        }
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
            int port = freePort();
            Path conf = writeConfig(workspace, data, database, port);
            log.accept("Loading " + dumpFile + " as database '" + database + "'");
            Commands.run(List.of(admin(), "database", "load", "--from-stdin", "--overwrite-destination=true", database),
                    environment(conf), dumpFile, log);
            return launch(workspace, conf, port, log, true, 60);
        } catch (RuntimeException e) {
            workspace.delete(root -> { });
            throw e;
        }
    }

    @Override
    public RunningInstance serveDataDirectory(Path dataDirectory, String database, Consumer<String> log) {
        Workspace workspace = Workspace.create(settings.workDir(), "serve");
        try {
            int port = freePort();
            Path conf = writeConfig(workspace, dataDirectory, database, port);
            return launch(workspace, conf, port, log, true, 600);
        } catch (RuntimeException e) {
            workspace.delete(root -> { });
            throw e;
        }
    }

    @Override
    public void importDatabase(Path dataDirectory, String database, ImportFiles files, Consumer<String> log) {
        Workspace workspace = Workspace.create(settings.workDir(), "import");
        try {
            Path conf = writeConfig(workspace, dataDirectory, database, 0);
            List<String> command = new ArrayList<>(List.of(admin()));
            command.addAll(ImportFiles.arguments(database, files.directory().toAbsolutePath() + "/",
                    workspace.root().resolve("import.report").toString(), files));
            Commands.run(command, environment(conf), null, log);
        } finally {
            workspace.delete(root -> { });
        }
    }

    @Override
    public void checkDatabase(Path dataDirectory, String database, Consumer<String> log) {
        Workspace workspace = Workspace.create(settings.workDir(), "check");
        try {
            Path conf = writeConfig(workspace, dataDirectory, database, 0);
            Commands.run(List.of(admin(), "database", "check", "--report-path=" + workspace.root().toAbsolutePath(), database),
                    environment(conf), null, log);
        } finally {
            workspace.delete(root -> { });
        }
    }

    /** Starts the server of the configuration in {@code conf}; closing it stops it and deletes the workspace. */
    private RunningInstance launch(Workspace workspace, Path conf, int port, Consumer<String> log, boolean ownsWorkspaceData,
                                   int stopSeconds) {
        Process server = null;
        try {
            server = Commands.start(List.of(settings.neo4jHome().resolve("bin").resolve("neo4j").toString(), "console"),
                    environment(conf), null);
            Process running = server;
            Thread drain = Thread.ofVirtual().start(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.accept(line);
                    }
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            String uri = "bolt://127.0.0.1:" + port;
            log.accept("Waiting for " + uri);
            BoltWait.until(uri, settings.startTimeout(), () -> running.isAlive() ? null : "exit code " + running.exitValue());
            log.accept("Temporary Neo4j is ready on " + uri);
            return new Instance(uri, () -> {
                stop(running, stopSeconds);
                drain.interrupt();
                workspace.delete(root -> { });
            });
        } catch (IOException e) {
            cleanUp(server, workspace);
            throw new InstanceException("Could not start Neo4j: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            cleanUp(server, workspace);
            throw e;
        }
    }

    private static void cleanUp(Process server, Workspace workspace) {
        if (server != null) {
            stop(server);
        }
        workspace.delete(root -> { });
    }

    private String admin() {
        return settings.neo4jHome().resolve("bin").resolve("neo4j-admin").toString();
    }

    private Map<String, String> environment(Path conf) {
        return Map.of("NEO4J_HOME", settings.neo4jHome().toAbsolutePath().toString(),
                "NEO4J_CONF", conf.toAbsolutePath().toString());
    }

    /** A configuration directory of its own: data where asked, everything else inside the work directory. */
    private Path writeConfig(Workspace workspace, Path data, String database, int boltPort) {
        try {
            Path conf = workspace.directory("conf");
            List<String> lines = new ArrayList<>(List.of(
                    "server.directories.data=" + data.toAbsolutePath(),
                    "server.directories.logs=" + workspace.directory("logs").toAbsolutePath(),
                    "server.directories.run=" + workspace.directory("run").toAbsolutePath(),
                    "server.directories.lib=" + settings.neo4jHome().resolve("lib").toAbsolutePath(),
                    "initial.dbms.default_database=" + database,
                    "dbms.security.auth_enabled=false",
                    "server.http.enabled=false",
                    "server.https.enabled=false",
                    "server.memory.heap.initial_size=" + settings.heapSize(),
                    "server.memory.heap.max_size=" + settings.heapSize(),
                    "server.memory.pagecache.size=" + settings.pageCacheSize()));
            if (boltPort > 0) {
                lines.add("server.bolt.listen_address=127.0.0.1:" + boltPort);
            }
            Files.write(conf.resolve("neo4j.conf"), lines);
            for (String file : List.of("server-logs.xml", "user-logs.xml")) {
                Path original = settings.neo4jHome().resolve("conf").resolve(file);
                if (Files.isRegularFile(original)) {
                    Files.copy(original, conf.resolve(file));
                }
            }
            return conf;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the temporary Neo4j server", e);
        }
    }

    private static void stop(Process process) {
        stop(process, 60);
    }

    private static void stop(Process process, int seconds) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Instance(String boltUri, Runnable onClose) implements RunningInstance {
        @Override
        public void close() {
            onClose.run();
        }
    }
}
