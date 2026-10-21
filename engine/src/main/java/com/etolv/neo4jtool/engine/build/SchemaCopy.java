package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

/**
 * The statements that recreate the source's indexes and constraints. The import does not create them (its
 * {@code --schema} option needs the block format, which Community does not have), so the split runs them on the new
 * database afterwards. Lookup indexes exist in every database and the index behind a constraint comes with the
 * constraint, so neither is listed.
 */
final class SchemaCopy {

    private SchemaCopy() {
    }

    static List<String> statements(Neo4jClient source) {
        List<String> statements = new ArrayList<>();
        try (Session session = source.readSession()) {
            for (Record r : session.run("SHOW CONSTRAINTS YIELD name, createStatement RETURN createStatement ORDER BY name").list()) {
                statements.add(r.get("createStatement").asString());
            }
            for (Record r : session.run("SHOW INDEXES YIELD name, type, owningConstraint, createStatement "
                    + "WHERE type <> 'LOOKUP' AND owningConstraint IS NULL RETURN createStatement ORDER BY name").list()) {
                statements.add(r.get("createStatement").asString());
            }
        }
        return statements;
    }
}
