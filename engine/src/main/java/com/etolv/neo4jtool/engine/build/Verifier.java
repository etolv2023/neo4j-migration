package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

/**
 * Reads the finished database back and compares it with what the export wrote: the same node ids (no more, no fewer),
 * and, over everything about every node and relationship, the same fingerprint. A value that changed type or
 * content on the way would show up here.
 */
final class Verifier {

    private Verifier() {
    }

    /** What the new database must contain: its node ids, and the totals of everything that was exported. */
    record Expected(IdSet ids, long nodes, long relationships, long nodeFingerprint, long relationshipFingerprint) {

        static Expected of(IdSet ids, CsvExporter.Export... exports) {
            long nodes = 0;
            long relationships = 0;
            long nodeHash = 0;
            long relationshipHash = 0;
            for (CsvExporter.Export export : exports) {
                nodes += export.nodes();
                relationships += export.relationships();
                nodeHash += export.nodeFingerprint();
                relationshipHash += export.relationshipFingerprint();
            }
            return new Expected(ids, nodes, relationships, nodeHash, relationshipHash);
        }
    }

    static void verify(Neo4jClient built, Expected expected) {
        IdSet ids = expected.ids();
        long nodes = 0;
        long nodeHash = 0;
        long unexpected = 0;
        long firstUnexpected = -1;
        try (Session session = built.readSession()) {
            Result result = session.run("MATCH (n) RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props");
            while (result.hasNext()) {
                Record r = result.next();
                long id = r.get("id").asLong();
                if (!ids.contains(id)) {
                    if (unexpected++ == 0) {
                        firstUnexpected = id;
                    }
                }
                List<String> labels = r.get("labels").asList(Value::asString);
                Map<String, Value> props = r.get("props").asMap(v -> v);
                nodeHash += Fingerprint.node(id, labels, props);
                nodes++;
            }
        }
        if (unexpected > 0) {
            throw new BuildException("The new database has " + unexpected + " nodes whose ids were not selected (the first is "
                    + firstUnexpected + "). It is not what was asked for and has been discarded.");
        }
        if (nodes != expected.nodes()) {
            throw new BuildException("The new database has " + nodes + " nodes but " + expected.nodes()
                    + " were exported. It has been discarded.");
        }
        if (nodeHash != expected.nodeFingerprint()) {
            throw new BuildException("The nodes of the new database differ from the ones that were exported (labels or "
                    + "properties changed on the way). It has been discarded.");
        }

        long relationships = 0;
        long relationshipHash = 0;
        try (Session session = built.readSession()) {
            Result result = session.run("MATCH (a)-[r]->(b) RETURN id(a) AS a, id(b) AS b, type(r) AS type, properties(r) AS props");
            while (result.hasNext()) {
                Record r = result.next();
                relationshipHash += Fingerprint.relationship(r.get("a").asLong(), r.get("b").asLong(), r.get("type").asString(),
                        r.get("props").asMap(v -> v));
                relationships++;
            }
        }
        if (relationships != expected.relationships()) {
            throw new BuildException("The new database has " + relationships + " relationships but " + expected.relationships()
                    + " were exported. It has been discarded.");
        }
        if (relationshipHash != expected.relationshipFingerprint()) {
            throw new BuildException("The relationships of the new database differ from the ones that were exported. "
                    + "It has been discarded.");
        }
    }
}
