package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

/** Reads node ids from a database (read only). */
final class Ids {

    private Ids() {
    }

    static IdSet selected(Neo4jClient client, SelectionConfig selection) {
        CypherQuery query = SelectionQueryBuilder.idStream(selection);
        return read(client, query.text(), query.params());
    }

    static IdSet all(Neo4jClient client) {
        return read(client, "MATCH (n) RETURN id(n) AS id", java.util.Map.of());
    }

    private static IdSet read(Neo4jClient client, String text, java.util.Map<String, Object> params) {
        IdSet.Collector ids = new IdSet.Collector();
        try (Session session = client.readSession()) {
            Result result = session.run(text, params);
            while (result.hasNext()) {
                Record r = result.next();
                ids.add(r.get("id").asLong());
            }
        }
        return ids.build();
    }
}
