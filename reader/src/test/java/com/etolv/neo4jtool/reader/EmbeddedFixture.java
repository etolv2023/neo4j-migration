package com.etolv.neo4jtool.reader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;

/** Makes a store with the embedded database, in a directory, and closes it cleanly so that it can be opened again. */
final class EmbeddedFixture {

    private EmbeddedFixture() {
    }

    /** Runs the statements in a new store under {@code home} and returns its data directory. */
    static Path create(Path home, String[] statements, Map<String, Object> parameters) throws Exception {
        Files.createDirectories(home);
        DatabaseManagementService dbms = new DatabaseManagementServiceBuilder(home)
                .setConfig(GraphDatabaseSettings.pagecache_memory, 256L * 1024 * 1024)
                .build();
        try {
            GraphDatabaseService db = dbms.database("neo4j");
            for (String statement : statements) {
                try (Transaction tx = db.beginTx()) {
                    tx.execute(statement, parameters).close();
                    tx.commit();
                }
            }
            try (Transaction tx = db.beginTx()) {
                tx.execute("CALL db.awaitIndexes(60)").close();
                tx.commit();
            }
        } finally {
            dbms.shutdown();
        }
        return home.resolve("data");
    }
}
