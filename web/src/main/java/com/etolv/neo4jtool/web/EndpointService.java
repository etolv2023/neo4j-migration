package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.InstanceRunners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Makes an endpoint readable over Bolt: connects to a running server, or starts a throw-away one on a dump or a stopped data directory. */
@Service
public class EndpointService {

    /** An endpoint that is ready to read. Closing it stops any throw-away server that was started for it. */
    public static final class Opened implements AutoCloseable {
        private final Neo4jClient client;
        private final RunningInstance instance;

        private Opened(Neo4jClient client, RunningInstance instance) {
            this.client = client;
            this.instance = instance;
        }

        public Neo4jClient client() {
            return client;
        }

        @Override
        public void close() {
            try {
                client.close();
            } finally {
                if (instance != null) {
                    instance.close();
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
                    endpoint.database()), null);
        }
        AppSettings current = settings.get();
        InstanceRunner runner = InstanceRunners.create(current.instanceSettings());
        Path where = Path.of(endpoint.path());
        RunningInstance instance = endpoint.kind() == Endpoint.Kind.DUMP
                ? runner.startFromDump(where, endpoint.database(), log)
                : runner.startFromStoppedDirectory(where, endpoint.database(), Path.of(current.workDir()), log);
        return connect(new ConnectionSettings(instance.boltUri(), null, null, endpoint.database()), instance);
    }

    private static Opened connect(ConnectionSettings connection, RunningInstance instance) {
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
        return new Opened(client, instance);
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
