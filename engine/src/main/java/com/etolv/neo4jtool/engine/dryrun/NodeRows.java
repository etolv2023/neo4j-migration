package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.Cypher;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/** Shared helpers for reading nodes by id. */
final class NodeRows {

    /** What identifies a node apart from its id: its label set and its org value. */
    record NodeRow(List<String> labels, Object org) {
    }

    private NodeRows() {
    }

    static String byIdQuery(String orgProperty) {
        return "MATCH (n) WHERE id(n) IN $ids RETURN id(n) AS id, labels(n) AS labels, n."
                + Cypher.ident(orgProperty) + " AS org";
    }

    static List<String> sortedLabels(Value labels) {
        List<String> list = new ArrayList<>(labels.asList(Value::asString));
        if (list.size() > 1) {
            Collections.sort(list);
        }
        return list;
    }

    static Object orgValue(Value value) {
        return value.isNull() ? null : value.asObject();
    }

    static Map<String, Object> idsParam(long[] ids) {
        return Map.of("ids", Values.value(ids));
    }

    /** Looks up the given ids; ids that do not exist are simply absent from the result. */
    static Long2ObjectOpenHashMap<NodeRow> fetch(Neo4jClient client, long[] ids, String orgProperty) {
        Long2ObjectOpenHashMap<NodeRow> found = new Long2ObjectOpenHashMap<>();
        if (ids.length == 0) {
            return found;
        }
        try (Session session = client.readSession()) {
            Result result = session.run(byIdQuery(orgProperty), idsParam(ids));
            while (result.hasNext()) {
                Record r = result.next();
                found.put(r.get("id").asLong(), new NodeRow(sortedLabels(r.get("labels")), orgValue(r.get("org"))));
            }
        }
        return found;
    }
}
