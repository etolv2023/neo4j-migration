package com.etolv.neo4jtool.reader;

import com.etolv.neo4jtool.engine.build.BuildException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.ConnectorPortRegister;
import org.neo4j.configuration.connectors.ConnectorType;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/**
 * A database opened for reading only, straight from its store files.
 *
 * <p>The files are not touched: the server runs in a scratch directory of its own (with its own {@code system}
 * database) and the store is linked into it, and the database is switched to read-only. Neo4j still takes its lock file in
 * the store, so a running server on the same directory makes this fail, as it should.
 */
final class Store implements AutoCloseable {

    private final DatabaseManagementService dbms;
    private final GraphDatabaseAPI database;
    private final Path home;
    private final String boltUri;

    /**
     * @param dataDirectory the server's data directory (with {@code databases/} and {@code transactions/})
     * @param scratch       where the scratch directory is made
     * @param bolt          also serve Bolt on a free local port, for the parts of the tool that ask Cypher questions
     */
    Store(Path dataDirectory, String databaseName, Path scratch, long pageCacheBytes, boolean bolt) {
        Path source = dataDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(source.resolve("databases").resolve(databaseName))) {
            throw new BuildException("databases/" + databaseName + " is not in " + source);
        }
        try {
            Files.createDirectories(scratch);
            home = Files.createTempDirectory(scratch, "reader-");
            Path data = Files.createDirectories(home.resolve("data"));
            Files.createDirectories(data.resolve("databases"));
            Files.createDirectories(data.resolve("transactions"));
            Files.createSymbolicLink(data.resolve("databases").resolve(databaseName), source.resolve("databases").resolve(databaseName));
            Path logs = source.resolve("transactions").resolve(databaseName);
            if (Files.isDirectory(logs)) {
                Files.createSymbolicLink(data.resolve("transactions").resolve(databaseName), logs);
            }
        } catch (IOException e) {
            throw new BuildException("Could not prepare a scratch directory in " + scratch + ": " + e.getMessage(), e);
        }
        DatabaseManagementServiceBuilder builder = new DatabaseManagementServiceBuilder(home)
                .setConfig(GraphDatabaseSettings.pagecache_memory, pageCacheBytes)
                .setConfig(GraphDatabaseSettings.initial_default_database, databaseName)
                .setConfig(GraphDatabaseSettings.read_only_databases, Set.of(databaseName))
                .setConfig(GraphDatabaseSettings.auth_enabled, false);
        if (bolt) {
            builder.setConfig(BoltConnector.enabled, true)
                    .setConfig(BoltConnector.listen_address, new SocketAddress("127.0.0.1", 0));
        }
        DatabaseManagementService started = null;
        try {
            started = builder.build();
            this.dbms = started;
            this.database = (GraphDatabaseAPI) started.database(databaseName);
            if (!database.isAvailable()) {
                throw new BuildException("The database '" + databaseName + "' is not available");
            }
            if (bolt) {
                var address = database.getDependencyResolver().resolveDependency(ConnectorPortRegister.class)
                        .getLocalAddress(ConnectorType.BOLT);
                boltUri = "bolt://" + address.getHost() + ":" + address.getPort();
            } else {
                boltUri = null;
            }
        } catch (RuntimeException e) {
            if (started != null) {
                started.shutdown();
            }
            deleteHome();
            if (e instanceof BuildException) {
                throw e;
            }
            throw new BuildException("Could not open the store in " + source + " (is a Neo4j server running on it, or was it "
                    + "not stopped cleanly?): " + e.getMessage(), e);
        }
    }

    GraphDatabaseAPI database() {
        return database;
    }

    String boltUri() {
        return boltUri;
    }

    @Override
    public void close() {
        try {
            dbms.shutdown();
        } finally {
            deleteHome();
        }
    }

    /** Removes the scratch directory. The links to the store are removed, never what they point to. */
    private void deleteHome() {
        try {
            Files.walkFileTree(home, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // a scratch directory in the work area; the next clean-up of the work area gets it
        }
    }
}
