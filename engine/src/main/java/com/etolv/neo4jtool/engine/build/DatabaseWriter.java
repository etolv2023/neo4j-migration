package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * Writes to the throw-away server that holds the database the split has just built. This is the only place in the
 * tool that opens a session in write mode, and it only ever talks to that server: the source and the target of a
 * migration are reached through {@link Neo4jClient}, whose sessions are read-only.
 */
final class DatabaseWriter implements AutoCloseable {

    private static final int FIXUP_BATCH = 1000;

    private final Driver driver;
    private final String database;

    DatabaseWriter(String boltUri, String database) {
        this.driver = GraphDatabase.driver(boltUri, AuthTokens.none());
        this.database = database;
        awaitDatabase();
    }

    /** The server accepts connections a little before the database is open. */
    private void awaitDatabase() {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try (Session session = session()) {
                session.run("RETURN 1").consume();
                return;
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BuildException("Interrupted while waiting for the new database", e);
            }
        }
        throw new BuildException("The new database did not open in time: " + (last == null ? "" : last.getMessage()));
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(database));
    }

    /**
     * Writes the properties the CSV import could not carry. The export wrote each value, with its type, to a file; they are
     * set here in batches, several batches at a time.
     */
    void applyFixups(Fixups fixups, int threads) {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<java.util.concurrent.Future<Long>> futures = new ArrayList<>();
            for (java.nio.file.Path file : fixups.files()) {
                try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ValueJson.Line item = ValueJson.read(line);
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", item.id());
                        row.put("key", item.key());
                        row.put("value", item.value().asObject());
                        rows.add(row);
                        if (rows.size() >= FIXUP_BATCH) {
                            futures.add(pool.submit(write(rows)));
                            rows = new ArrayList<>();
                            // keep a few batches in flight, not the whole file
                            while (futures.size() > threads * 4L) {
                                futures.remove(0).get();
                            }
                        }
                    }
                    if (!rows.isEmpty()) {
                        futures.add(pool.submit(write(rows)));
                    }
                } catch (java.io.IOException e) {
                    throw new BuildException("Could not read " + file + ": " + e.getMessage(), e);
                }
            }
            for (java.util.concurrent.Future<Long> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("Interrupted while writing properties afterwards", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BuildException("Writing properties afterwards failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private java.util.concurrent.Callable<Long> write(List<Map<String, Object>> rows) {
        return () -> {
            try (Session session = session()) {
                long set = session.executeWrite(tx -> tx.run(
                        "UNWIND $rows AS row MATCH (n) WHERE id(n) = row.id SET n[row.key] = row.value RETURN count(*) AS c",
                        Map.of("rows", rows)).single().get("c").asLong());
                if (set != rows.size()) {
                    throw new BuildException("Writing properties afterwards matched " + set + " nodes instead of " + rows.size());
                }
                return set;
            }
        };
    }

    /** Runs the statements, one by one, and waits until every index is online. */
    void createSchema(List<String> statements) {
        for (String statement : statements) {
            try (Session session = session()) {
                session.run(statement).consume();
            } catch (RuntimeException e) {
                throw new BuildException("Could not create an index or constraint in the new database: " + statement
                        + "\n" + e.getMessage(), e);
            }
        }
        try (Session session = session()) {
            session.run("CALL db.awaitIndexes(1800)").consume();
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
