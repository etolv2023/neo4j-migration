package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

/** Reads a whole small database into text so that tests can compare two databases exactly, node by node. */
final class GraphCompare {

    /** What identifies a node apart from its id: labels and the org value. */
    record Node(String description, String identity, Object org) {
    }

    record Graph(Map<Long, Node> nodes, List<String> relationships, List<String> schema) {
    }

    private GraphCompare() {
    }

    static Graph read(Session session) {
        Map<Long, Node> nodes = new TreeMap<>();
        for (Record r : session.run("MATCH (n) RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props").list()) {
            List<String> labels = new ArrayList<>(r.get("labels").asList(Value::asString));
            labels.sort(String::compareTo);
            Map<String, Object> props = describe(r.get("props"));
            Object org = props.get("org_id");
            nodes.put(r.get("id").asLong(), new Node(labels + " " + props, labels + "|" + org, org));
        }
        List<String> relationships = new ArrayList<>();
        for (Record r : session.run("MATCH (a)-[r]->(b) RETURN id(a) AS a, id(b) AS b, type(r) AS type, properties(r) AS props").list()) {
            relationships.add(relationship(r.get("a").asLong(), r.get("b").asLong(), r.get("type").asString(), describe(r.get("props"))));
        }
        return new Graph(nodes, relationships, schema(session));
    }

    /** Relationship ids are not kept, so a relationship is identified by its ends, type and properties. */
    static String relationship(long start, long end, String type, Map<String, Object> props) {
        return start + "->" + end + " " + type + " " + props;
    }

    static Map<String, Object> describe(Value properties) {
        Map<String, Object> props = new TreeMap<>();
        properties.asMap().forEach((k, v) -> props.put(k, normalize(v)));
        return props;
    }

    static List<String> schema(Session session) {
        List<String> rows = new ArrayList<>();
        rows.addAll(session.run("SHOW CONSTRAINTS YIELD name, type, labelsOrTypes, properties RETURN name, type, labelsOrTypes, properties")
                .list(GraphCompare::schemaRow));
        rows.addAll(session.run("SHOW INDEXES YIELD name, type, labelsOrTypes, properties, owningConstraint "
                + "WHERE type <> 'LOOKUP' AND owningConstraint IS NULL RETURN name, type, labelsOrTypes, properties").list(GraphCompare::schemaRow));
        rows.sort(String::compareTo);
        return rows;
    }

    private static String schemaRow(Record r) {
        return r.get("name").asString() + " " + r.get("type").asString() + " " + r.get("labelsOrTypes").asList() + " "
                + r.get("properties").asList();
    }

    static Object normalize(Object value) {
        if (value instanceof byte[] bytes) {
            return "bytes:" + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GraphCompare::normalize).toList();
        }
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(GraphCompare::normalize).toList();
        }
        return value;
    }
}
