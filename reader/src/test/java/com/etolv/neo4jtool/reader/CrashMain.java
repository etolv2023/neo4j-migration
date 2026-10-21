package com.etolv.neo4jtool.reader;

import java.nio.file.Path;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;

/** Writes to a store and dies without shutting it down, as a killed server would. Started as a child process by a test. */
public final class CrashMain {

    private CrashMain() {
    }

    public static void main(String[] args) {
        DatabaseManagementService dbms = new DatabaseManagementServiceBuilder(Path.of(args[0])).build();
        GraphDatabaseService db = dbms.database("neo4j");
        for (int i = 0; i < 20; i++) {
            try (Transaction tx = db.beginTx()) {
                tx.execute("UNWIND range(1, 1000) AS i CREATE (:X {n: i})").close();
                tx.commit();
            }
        }
        Runtime.getRuntime().halt(9);
    }
}
