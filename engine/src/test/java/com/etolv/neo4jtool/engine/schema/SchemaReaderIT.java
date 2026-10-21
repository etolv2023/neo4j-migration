package com.etolv.neo4jtool.engine.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.LabelInfo;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.OrgCount;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaReaderIT {

    private Neo4jFixture neo4j;

    @BeforeAll
    void start() {
        neo4j = Neo4jFixture.start();
        neo4j.run("CREATE (:Booking {org_id: 1, ref: 'a'}), (:Booking {org_id: 1, ref: 'b'}), "
                + "(:Booking {org_id: 2, ref: 'c'}), (:Service {org_id: 1}), (:Service), (:Orphan), ()");
        neo4j.run("MATCH (b:Booking {ref: 'a'}), (s:Service {org_id: 1}) CREATE (b)-[:HAS_SERVICE]->(s)");
        neo4j.run("CREATE CONSTRAINT booking_ref FOR (b:Booking) REQUIRE b.ref IS UNIQUE");
        neo4j.run("CREATE INDEX service_org FOR (s:Service) ON (s.org_id)");
        neo4j.query("CALL db.awaitIndexes(60)");
    }

    @AfterAll
    void stop() {
        neo4j.close();
    }

    @Test
    void readsCountsLabelsRelationshipsAndPropertyKeys() {
        SchemaSnapshot snapshot = new SchemaReader(neo4j.client(), "org_id", true).read();

        assertThat(snapshot.serverVersion()).startsWith("5.26");
        assertThat(snapshot.database()).isEqualTo("neo4j");
        assertThat(snapshot.totalNodes()).isEqualTo(7);
        assertThat(snapshot.totalRelationships()).isEqualTo(1);
        assertThat(snapshot.labels()).extracting(LabelInfo::name).containsExactly("Booking", "Orphan", "Service");
        assertThat(snapshot.labels()).extracting(LabelInfo::count).containsExactly(3L, 1L, 2L);
        assertThat(snapshot.relationshipTypes()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("HAS_SERVICE");
            assertThat(t.count()).isEqualTo(1);
        });
        assertThat(snapshot.propertyKeys()).contains("org_id", "ref");
    }

    @Test
    void breaksCountsDownByOrgIncludingNodesWithoutOne() {
        SchemaSnapshot snapshot = new SchemaReader(neo4j.client(), "org_id", true).read();

        assertThat(snapshot.orgBreakdownIncluded()).isTrue();
        LabelInfo booking = snapshot.labels().stream().filter(l -> l.name().equals("Booking")).findFirst().orElseThrow();
        assertThat(booking.byOrg()).containsExactlyInAnyOrder(new OrgCount(1L, 2), new OrgCount(2L, 1));
        LabelInfo service = snapshot.labels().stream().filter(l -> l.name().equals("Service")).findFirst().orElseThrow();
        assertThat(service.byOrg()).containsExactlyInAnyOrder(new OrgCount(1L, 1), new OrgCount(null, 1));
        assertThat(snapshot.unlabeledByOrg()).containsExactly(new OrgCount(null, 1));
    }

    @Test
    void orgBreakdownCanBeSwitchedOffBecauseItScansEveryNode() {
        SchemaSnapshot snapshot = new SchemaReader(neo4j.client(), "org_id", false).read();

        assertThat(snapshot.orgBreakdownIncluded()).isFalse();
        assertThat(snapshot.labels()).allSatisfy(label -> assertThat(label.byOrg()).isEmpty());
        assertThat(snapshot.unlabeledByOrg()).isEmpty();
    }

    @Test
    void readsIndexesAndConstraints() {
        SchemaSnapshot snapshot = new SchemaReader(neo4j.client(), "org_id", false).read();

        assertThat(snapshot.indexes()).anySatisfy(index -> {
            assertThat(index.name()).isEqualTo("service_org");
            assertThat(index.labelsOrTypes()).containsExactly("Service");
            assertThat(index.properties()).containsExactly("org_id");
            assertThat(index.state()).isEqualTo("ONLINE");
        });
        assertThat(snapshot.indexes()).anySatisfy(index -> assertThat(index.type()).isEqualTo("LOOKUP"));
        assertThat(snapshot.constraints()).singleElement().satisfies(constraint -> {
            assertThat(constraint.name()).isEqualTo("booking_ref");
            assertThat(constraint.type()).isEqualTo("UNIQUENESS");
            assertThat(constraint.labelsOrTypes()).containsExactly("Booking");
            assertThat(constraint.properties()).containsExactly("ref");
        });
    }

    @Test
    void aSessionOpenedByTheToolCannotWrite() {
        try (var session = neo4j.client().readSession()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> session.run("CREATE (:Hacked)").consume())
                    .isInstanceOf(org.neo4j.driver.exceptions.ClientException.class);
        }
        assertThat(neo4j.query("MATCH (n:Hacked) RETURN count(n) AS c").get(0).get("c").asLong()).isZero();
    }
}
