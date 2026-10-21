package com.etolv.neo4jtool.engine.instance;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Gets a database that is not served by a running server into a state where the tool can read it over Bolt. */
public interface InstanceRunner {

    /**
     * Loads a dump file into a fresh database and starts a throw-away server on it.
     * The server is authentication-free and reachable from this machine only.
     *
     * @param database the name the database will have (the one the dump was taken from)
     */
    RunningInstance startFromDump(Path dumpFile, String database, Consumer<String> log);

    /**
     * Writes a dump of a database whose server is stopped. {@code dataDirectory} is the server's data directory (the one
     * with {@code databases/} and {@code transactions/} in it). The result is {@code <database>.dump} in
     * {@code outputDirectory}. Neo4j takes a lock inside the store while it dumps and changes nothing else.
     */
    Path dumpStoppedDatabase(Path dataDirectory, String database, Path outputDirectory, Consumer<String> log);

    /**
     * Dumps a stopped database and serves the dump, in one step. Closing the instance removes the dump as well.
     * The data directory is only touched the way {@link #dumpStoppedDatabase} describes.
     */
    default RunningInstance startFromStoppedDirectory(Path dataDirectory, String database, Path workDir, Consumer<String> log) {
        Workspace dumps = Workspace.create(workDir, "stopped");
        try {
            Path dump = dumpStoppedDatabase(dataDirectory, database, dumps.directory("dump"), log);
            RunningInstance instance = startFromDump(dump, database, log);
            return new RunningInstance() {
                @Override
                public String boltUri() {
                    return instance.boltUri();
                }

                @Override
                public void close() {
                    try {
                        instance.close();
                    } finally {
                        dumps.delete(root -> { });
                    }
                }
            };
        } catch (RuntimeException e) {
            dumps.delete(root -> { });
            throw e;
        }
    }

    /** A running throw-away server. Closing it stops the server and deletes its files. */
    interface RunningInstance extends AutoCloseable {

        String boltUri();

        @Override
        void close();
    }
}
