package com.etolv.neo4jtool.engine.schema;

import com.etolv.neo4jtool.engine.Cypher;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.ConstraintInfo;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.IndexInfo;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.LabelInfo;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.OrgCount;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.RelationshipTypeInfo;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

/** Reads labels, relationship types, indexes, constraints and counts. Read-only. */
public final class SchemaReader {

    private final Neo4jClient client;
    private final String orgProperty;
    private final boolean includeOrgBreakdown;

    /**
     * @param orgProperty         the node property that identifies the organisation, for example "org_id"
     * @param includeOrgBreakdown per-label, per-org counts need a scan of every node, so it can be switched off
     */
    public SchemaReader(Neo4jClient client, String orgProperty, boolean includeOrgBreakdown) {
        this.client = client;
        this.orgProperty = orgProperty;
        this.includeOrgBreakdown = includeOrgBreakdown;
    }

    public SchemaSnapshot read() {
        String version = client.serverVersion();
        try (Session session = client.readSession()) {
            long totalNodes = session.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong();
            long totalRels = session.run("MATCH ()-[r]->() RETURN count(r) AS c").single().get("c").asLong();

            List<LabelInfo> labels = new ArrayList<>();
            for (String label : strings(session, "CALL db.labels() YIELD label RETURN label ORDER BY label", "label")) {
                long count = session.run("MATCH (n:" + Cypher.ident(label) + ") RETURN count(n) AS c")
                        .single().get("c").asLong();
                List<OrgCount> byOrg = includeOrgBreakdown
                        ? orgCounts(session, "MATCH (n:" + Cypher.ident(label) + ")")
                        : List.of();
                labels.add(new LabelInfo(label, count, byOrg));
            }

            List<OrgCount> unlabeled = includeOrgBreakdown
                    ? orgCounts(session, "MATCH (n) WHERE size(labels(n)) = 0")
                    : List.of();

            List<RelationshipTypeInfo> relTypes = new ArrayList<>();
            for (String type : strings(session,
                    "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType ORDER BY relationshipType",
                    "relationshipType")) {
                long count = session.run("MATCH ()-[r:" + Cypher.ident(type) + "]->() RETURN count(r) AS c")
                        .single().get("c").asLong();
                relTypes.add(new RelationshipTypeInfo(type, count));
            }

            List<String> propertyKeys = strings(session,
                    "CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey ORDER BY propertyKey", "propertyKey");

            List<IndexInfo> indexes = new ArrayList<>();
            for (Record r : session.run("SHOW INDEXES YIELD name, type, entityType, labelsOrTypes, properties, state "
                    + "RETURN name, type, entityType, labelsOrTypes, properties, state ORDER BY name").list()) {
                indexes.add(new IndexInfo(r.get("name").asString(), r.get("type").asString(),
                        r.get("entityType").asString(), stringList(r.get("labelsOrTypes")),
                        stringList(r.get("properties")), r.get("state").asString()));
            }

            List<ConstraintInfo> constraints = new ArrayList<>();
            for (Record r : session.run("SHOW CONSTRAINTS YIELD name, type, entityType, labelsOrTypes, properties "
                    + "RETURN name, type, entityType, labelsOrTypes, properties ORDER BY name").list()) {
                constraints.add(new ConstraintInfo(r.get("name").asString(), r.get("type").asString(),
                        r.get("entityType").asString(), stringList(r.get("labelsOrTypes")),
                        stringList(r.get("properties"))));
            }

            return new SchemaSnapshot(version, client.database(), totalNodes, totalRels, orgProperty,
                    includeOrgBreakdown, labels, unlabeled, relTypes, propertyKeys, indexes, constraints);
        }
    }

    private List<OrgCount> orgCounts(Session session, String matchClause) {
        List<OrgCount> result = new ArrayList<>();
        String query = matchClause + " RETURN n." + Cypher.ident(orgProperty) + " AS org, count(*) AS c ORDER BY c DESC";
        for (Record r : session.run(query).list()) {
            Value org = r.get("org");
            result.add(new OrgCount(org.isNull() ? null : org.asObject(), r.get("c").asLong()));
        }
        return result;
    }

    private static List<String> strings(Session session, String query, String column) {
        List<String> result = new ArrayList<>();
        session.run(query).forEachRemaining(r -> result.add(r.get(column).asString()));
        return result;
    }

    private static List<String> stringList(Value value) {
        return value.isNull() ? List.of() : value.asList(Value::asString);
    }
}
