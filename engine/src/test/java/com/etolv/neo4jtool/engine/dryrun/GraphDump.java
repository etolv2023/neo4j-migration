package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

/** A whole small database held in memory, so tests can work out the right answer the slow, obvious way. */
final class GraphDump {

    record Node(long id, List<String> labels, Object org) {
    }

    record Rel(long id, String type, long start, long end) {
    }

    final Map<Long, Node> nodes = new TreeMap<>();
    final List<Rel> rels = new ArrayList<>();

    static GraphDump of(Neo4jFixture db) {
        GraphDump dump = new GraphDump();
        for (Record r : db.query("MATCH (n) RETURN id(n) AS id, labels(n) AS labels, n.org_id AS org")) {
            List<String> labels = new ArrayList<>(r.get("labels").asList(Value::asString));
            labels.sort(String::compareTo);
            Value org = r.get("org");
            dump.nodes.put(r.get("id").asLong(), new Node(r.get("id").asLong(), labels, org.isNull() ? null : org.asObject()));
        }
        for (Record r : db.query("MATCH (a)-[r]->(b) RETURN id(r) AS id, type(r) AS type, id(a) AS s, id(b) AS e")) {
            dump.rels.add(new Rel(r.get("id").asLong(), r.get("type").asString(), r.get("s").asLong(), r.get("e").asLong()));
        }
        return dump;
    }
}
