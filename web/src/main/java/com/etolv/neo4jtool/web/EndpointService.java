package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.build.FastReader;
import com.etolv.neo4jtool.engine.build.StoreSignature;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.fast.ReaderProcess;
import com.etolv.neo4jtool.engine.fast.Sizes;
import com.etolv.neo4jtool.engine.instance.Workspace;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.InstanceRunners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Makes an endpoint readable over Bolt: connects to a running server, or starts a throw-away one on a dump or a stopped data directory. */
@Service
public class EndpointService {

    /** An endpoint that is ready to read. Closing it stops any throw-away server that was started for it. */
    public static final class Opened implements AutoCloseable {
        private final Neo4jClient client;
        private final RunningInstance instance;
        private final FastReader fast;
        private final Runnable cleanUp;

        private Opened(Neo4jClient client, RunningInstance instance, FastReader fast, Runnable cleanUp) {
            this.client = client;
            this.instance = instance;
            this.fast = fast;
            this.cleanUp = cleanUp;
        }

        public Neo4jClient client() {
            return client;
        }

        /** Reads the store directly on several threads; null when this endpoint is read over Bolt only. */
        public FastReader fast() {
            return fast;
        }

        @Override
        public void close() {
            try {
                client.close();
            } finally {
                try {
                    if (fast != null) {
                        fast.close();
                    }
                } finally {
                    try {
                        if (instance != null) {
                            instance.close();
                        }
                    } finally {
                        if (cleanUp != null) {
                            cleanUp.run();
                        }
                    }
                }
            }
        }
    }

    private final EndpointStore endpoints;
    private final SettingsStore settings;

    public EndpointService(EndpointStore endpoints, SettingsStore settings) {
        this.endpoints = endpoints;
        this.settings = settings;
    }

    public Opened open(Endpoint endpoint, Consumer<String> log) {
        if (endpoint.kind() == Endpoint.Kind.LIVE) {
            log.accept("Connecting to " + endpoint.uri());
            return connect(new ConnectionSettings(endpoint.uri(), endpoint.username(), endpoints.password(endpoint),
                    endpoint.database()), null, null, null);
        }
        AppSettings current = settings.get();
        InstanceRunner runner = InstanceRunners.create(current.instanceSettings());
        Path where = Path.of(endpoint.path());
        if (current.fastReader()) {
            Opened fast = openFast(endpoint, current, runner, where, log);
            if (fast != null) {
                return fast;
            }
        }
        RunningInstance instance = endpoint.kind() == Endpoint.Kind.DUMP
                ? runner.startFromDump(where, endpoint.database(), log)
                : runner.startFromStoppedDirectory(where, endpoint.database(), Path.of(current.workDir()), log);
        return connect(new ConnectionSettings(instance.boltUri(), null, null, endpoint.database()), instance, null, null);
    }

    /** The reader process settings, or null when its jars cannot be found. */
    ReaderProcess.Settings readerSettings(AppSettings current) {
        String classpath = readerClasspath(current);
        if (classpath == null) {
            return null;
        }
        return new ReaderProcess.Settings(classpath, current.readerHeap(), Sizes.bytes(current.readerPageCache()),
                Path.of(current.workDir(), "reader"), Duration.ofMinutes(Math.max(1, current.startTimeoutMinutes())));
    }

    /** Starts readers on request; null when the fast reader is off or not installed. */
    public FastReader.Factory fastReaders() {
        AppSettings current = settings.get();
        if (!current.fastReader()) {
            return null;
        }
        ReaderProcess.Settings reader = readerSettings(current);
        return reader == null ? null : ReaderProcess.factory(reader);
    }

    /** Null, having changed nothing, when the fast reader cannot be used for this endpoint. */
    private Opened openFast(Endpoint endpoint, AppSettings current, InstanceRunner runner, Path where, Consumer<String> log) {
        ReaderProcess.Settings reader;
        try {
            reader = readerSettings(current);
        } catch (IllegalArgumentException e) {
            log.accept("Fast reader off: " + e.getMessage() + ". Using a temporary server.");
            return null;
        }
        if (reader == null) {
            log.accept("Fast reader off: its jars were not found (set the directory in Settings). Using a temporary server.");
            return null;
        }
        Workspace workspace = null;
        ReaderProcess process = null;
        Neo4jClient client = null;
        try {
            Path data;
            String before = null;
            if (endpoint.kind() == Endpoint.Kind.DUMP) {
                workspace = Workspace.create(Path.of(current.workDir()), "read");
                data = workspace.directory("data");
                runner.loadDump(where, endpoint.database(), data, log);
            } else {
                String problem = notOpenableInPlace(where, endpoint.database());
                if (problem != null) {
                    log.accept("Fast reader not used for '" + endpoint.name() + "': " + problem + ". Using a temporary server.");
                    return null;
                }
                data = where;
                before = StoreSignature.of(where, endpoint.database());
            }
            process = ReaderProcess.start(reader, data, endpoint.database(), true, log);
            client = new Neo4jClient(new ConnectionSettings(process.boltUri(), null, null, endpoint.database()));
            client.verify();
            final ReaderProcess started = process;
            final Workspace owned = workspace;
            final String signature = before;
            final Path directory = where;
            final String database = endpoint.database();
            Runnable cleanUp = () -> {
                if (signature != null) {
                    try {
                        if (!signature.equals(StoreSignature.of(directory, database))) {
                            log.accept("WARNING: the files of '" + database + "' in " + directory + " changed while they were read");
                        }
                    } catch (RuntimeException e) {
                        log.accept("Could not compare the files of '" + database + "' after reading: " + e.getMessage());
                    }
                }
                if (owned != null) {
                    runner.deleteWorkspace(owned);
                }
            };
            return new Opened(client, null, started, cleanUp);
        } catch (RuntimeException e) {
            log.accept("Fast reader failed for '" + endpoint.name() + "' (" + e.getMessage() + "). Using a temporary server.");
            if (client != null) {
                client.close();
            }
            if (process != null) {
                process.close();
            }
            if (workspace != null) {
                runner.deleteWorkspace(workspace);
            }
            return null;
        }
    }

    /**
     * The reader opens the big record files with write access (it never writes to them) and takes the lock file, so
     * the user of this application must be allowed to write there. Its small bookkeeping files are copied, so the
     * directories themselves need not be writable. Returns why not, or null.
     */
    private static String notOpenableInPlace(Path dataDirectory, String database) {
        Path store = dataDirectory.resolve("databases").resolve(database);
        if (!Files.isDirectory(store)) {
            return store + " is not there";
        }
        for (String name : List.of("neostore", "neostore.nodestore.db", "neostore.relationshipstore.db",
                "neostore.propertystore.db", "database_lock")) {
            Path file = store.resolve(name);
            if (Files.exists(file) && !Files.isWritable(file)) {
                return file + " is not writable by this application's user (Neo4j opens its store files for writing even to read)";
            }
        }
        return null;
    }

    /** The jars of the reader: the setting, or a directory next to the application. */
    private static String readerClasspath(AppSettings current) {
        List<Path> candidates = new ArrayList<>();
        if (current.readerLib() != null && !current.readerLib().isBlank()) {
            candidates.add(Path.of(current.readerLib()));
        } else {
            String property = System.getProperty("tool.reader.lib");
            if (property != null && !property.isBlank()) {
                candidates.add(Path.of(property));
            }
            Path cwd = Path.of("").toAbsolutePath();
            candidates.add(cwd.resolve("reader-lib"));
            candidates.add(cwd.resolve("reader").resolve("target").resolve("lib"));
            candidates.add(cwd.getParent() == null ? cwd : cwd.getParent().resolve("reader").resolve("target").resolve("lib"));
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && hasReaderJar(candidate)) {
                return candidate.toAbsolutePath() + "/*";
            }
        }
        return null;
    }

    private static boolean hasReaderJar(Path directory) {
        try (var files = Files.list(directory)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith("reader"));
        } catch (IOException e) {
            return false;
        }
    }

    private static Opened connect(ConnectionSettings connection, RunningInstance instance, FastReader fast, Runnable cleanUp) {
        Neo4jClient client = new Neo4jClient(connection);
        try {
            client.verify();
        } catch (RuntimeException e) {
            client.close();
            if (instance != null) {
                instance.close();
            }
            throw e;
        }
        return new Opened(client, instance, fast, cleanUp);
    }

    /** A quick check that costs nothing: connects to a server, or looks at the file or directory. */
    public String check(Endpoint endpoint) {
        try {
            switch (endpoint.kind()) {
                case LIVE -> {
                    try (Opened opened = open(endpoint, line -> { })) {
                        return "Connected, Neo4j " + opened.client().serverVersion();
                    }
                }
                case DUMP -> {
                    return "Found, " + megabytes(Files.size(Path.of(endpoint.path()))) + ". It is loaded into a temporary server when used.";
                }
                default -> {
                    if (!Files.isDirectory(Path.of(endpoint.path(), "databases", endpoint.database()))) {
                        throw new IllegalStateException("databases/" + endpoint.database() + " is not in " + endpoint.path());
                    }
                    return "Found. It is dumped and loaded into a temporary server when used.";
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + endpoint.path() + ": " + e.getMessage(), e);
        }
    }

    private static String megabytes(long bytes) {
        return String.format("%,.1f MB", bytes / 1024.0 / 1024.0);
    }
}
