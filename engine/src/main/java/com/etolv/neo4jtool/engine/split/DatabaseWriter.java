package com.etolv.neo4jtool.engine.split;

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
                throw new SplitException("Interrupted while waiting for the new database", e);
            }
        }
        throw new SplitException("The new database did not open in time: " + (last == null ? "" : last.getMessage()));
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(database));
    }

    /**
     * Writes the properties the CSV import could not carry. Each value is read from the source again and set with its
     * exact type.
     */
    void applyFixups(Neo4jClient source, Fixups fixups) {
        int total = fixups.size();
        for (int from = 0; from < total; from += FIXUP_BATCH) {
            int to = Math.min(total, from + FIXUP_BATCH);
            Map<Long, List<String>> keysById = new LinkedHashMap<>();
            for (int i = from; i < to; i++) {
                keysById.computeIfAbsent(fixups.id(i), k -> new ArrayList<>()).add(fixups.key(i));
            }
            long[] ids = keysById.keySet().stream().mapToLong(Long::longValue).toArray();
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Session read = source.readSession()) {
                for (Record r : read.run("UNWIND $ids AS i MATCH (n) WHERE id(n) = i RETURN id(n) AS id, properties(n) AS props",
                        Map.of("ids", Values.value(ids))).list()) {
                    long id = r.get("id").asLong();
                    Map<String, Value> props = r.get("props").asMap(v -> v);
                    for (String key : keysById.get(id)) {
                        Value value = props.get(key);
                        if (value == null) {
                            throw new SplitException("Node " + id + " no longer has the property '" + key
                                    + "'. The source changed during the split; run it again.");
                        }
                        Map<String, Object> row = new HashMap<>();
                        row.put("id", id);
                        row.put("key", key);
                        row.put("value", value.asObject());
                        rows.add(row);
                    }
                }
            }
            if (rows.size() != to - from) {
                throw new SplitException("Some nodes that need a property written afterwards are gone from the source. "
                        + "The source changed during the split; run it again.");
            }
            try (Session session = session()) {
                long set = session.executeWrite(tx -> tx.run(
                        "UNWIND $rows AS row MATCH (n) WHERE id(n) = row.id SET n[row.key] = row.value RETURN count(*) AS c",
                        Map.of("rows", rows)).single().get("c").asLong());
                if (set != rows.size()) {
                    throw new SplitException("Writing properties afterwards matched " + set + " nodes instead of " + rows.size());
                }
            }
        }
    }

    /** Runs the statements, one by one, and waits until every index is online. */
    void createSchema(List<String> statements) {
        for (String statement : statements) {
            try (Session session = session()) {
                session.run(statement).consume();
            } catch (RuntimeException e) {
                throw new SplitException("Could not create an index or constraint in the new database: " + statement
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
