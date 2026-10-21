package com.etolv.neo4jtool.engine.dryrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.Gate;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.IdRun;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.LabelCount;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.OrgCount;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The restore story with numbers small enough to check by hand.
 *
 * <pre>
 * ids  0..9   Service  org 1
 * ids 10..19  Service  org 2
 * ids 20..29  Booking  org 2   (Booking 20+i -> Service 10+i, plus Booking 20 -> Service 0)
 * ids 30..34  Booking  org 3
 * </pre>
 *
 * The backup has all of it. The live database lost ids 0, 10-12 and 20-24, and two nodes changed:
 * Booking 26 got another label and Service 15 moved to org 9.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DryRunScenarioIT {

    private Neo4jFixture backup;
    private Neo4jFixture live;
    private final DryRunService service = new DryRunService();

    @BeforeAll
    void start() {
        backup = Neo4jFixture.start();
        live = Neo4jFixture.start();
        createData(backup);
        createData(live);
        live.run("MATCH (n) WHERE id(n) IN range(20, 24) + [10, 11, 12, 0] DETACH DELETE n");
        live.run("MATCH (b) WHERE id(b) = 26 SET b:Archived");
        live.run("MATCH (s) WHERE id(s) = 15 SET s.org_id = 9");
    }

    @AfterAll
    void stop() {
        backup.close();
        live.close();
    }

    private static void createData(Neo4jFixture db) {
        db.run("UNWIND range(1, 10) AS i CREATE (:Service {org_id: 1})");
        db.run("UNWIND range(1, 10) AS i CREATE (:Service {org_id: 2})");
        db.run("UNWIND range(1, 10) AS i CREATE (:Booking {org_id: 2})");
        db.run("UNWIND range(1, 5) AS i CREATE (:Booking {org_id: 3})");
        db.run("UNWIND range(0, 9) AS i MATCH (b) WHERE id(b) = 20 + i MATCH (s) WHERE id(s) = 10 + i "
                + "CREATE (b)-[:HAS_SERVICE]->(s)");
        db.run("MATCH (b) WHERE id(b) = 20 MATCH (s) WHERE id(s) = 0 CREATE (b)-[:HAS_SERVICE]->(s)");
        var stats = db.query("MATCH (n) RETURN min(id(n)) AS lo, max(id(n)) AS hi, count(n) AS c").get(0);
        assertThat(stats.get("lo").asLong()).isZero();
        assertThat(stats.get("hi").asLong()).isEqualTo(34);
        assertThat(stats.get("c").asLong()).isEqualTo(35);
    }

    private DryRunReport restore(String selectionYaml) {
        return service.run(backup.client(), live.client(), SelectionConfigParser.parse(selectionYaml),
                DryRunOptions.defaults(), DryRunProgress.NONE);
    }

    @Test
    void restoringOrg2IsBlockedBecauseTwoIdsNowHoldDifferentNodes() {
        DryRunReport report = restore("org_ids: [2]");

        assertThat(report.sourceVersion()).startsWith("5.26");
        assertThat(report.selectedNodes()).isEqualTo(20);
        assertThat(report.selectedByLabel()).containsExactly(new LabelCount("Booking", 10), new LabelCount("Service", 10));
        assertThat(report.selectedByOrg()).containsExactly(new OrgCount(2L, 20));

        var nodes = report.nodes();
        assertThat(nodes.missing()).isEqualTo(8);
        assertThat(nodes.same()).isEqualTo(10);
        assertThat(nodes.conflict()).isEqualTo(2);
        assertThat(nodes.missingByLabel()).containsExactly(new LabelCount("Booking", 5), new LabelCount("Service", 3));
        assertThat(nodes.conflictByLabel()).containsExactlyInAnyOrder(new LabelCount("Booking", 1), new LabelCount("Service", 1));
        assertThat(nodes.missingByOrg()).containsExactly(new OrgCount(2L, 8));
        assertThat(nodes.conflictByOrg()).containsExactly(new OrgCount(2L, 2));
        assertThat(nodes.missingRunCount()).isEqualTo(2);
        assertThat(nodes.largestMissingRuns()).containsExactly(new IdRun(20, 24, 5), new IdRun(10, 12, 3));
        assertThat(nodes.missingSamples()).extracting(DryRunReport.NodeSample::id)
                .containsExactly(10L, 11L, 12L, 20L, 21L, 22L, 23L, 24L);
        assertThat(nodes.conflictSamples()).satisfiesExactly(
                first -> {
                    assertThat(first.id()).isEqualTo(15);
                    assertThat(first.sourceOrg()).isEqualTo(2L);
                    assertThat(first.targetOrg()).isEqualTo(9L);
                },
                second -> {
                    assertThat(second.id()).isEqualTo(26);
                    assertThat(second.sourceLabels()).containsExactly("Booking");
                    assertThat(second.targetLabels()).containsExactly("Archived", "Booking");
                });

        var rels = report.relationships();
        assertThat(rels.scope()).isEqualTo("missing-nodes");
        assertThat(rels.total()).isEqualTo(6);
        assertThat(rels.internal()).isEqualTo(3);
        assertThat(rels.resolved()).isEqualTo(2);
        assertThat(rels.conflict()).isZero();
        assertThat(rels.dangling()).isEqualTo(1);
        assertThat(rels.danglingSamples()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("HAS_SERVICE");
            assertThat(s.startNode()).isEqualTo(20);
            assertThat(s.endNode()).isZero();
        });

        assertThat(report.gate()).isEqualTo(Gate.BLOCKED);
        assertThat(report.gateReasons()).anyMatch(r -> r.startsWith("2 selected ids are already used"));
    }

    @Test
    void excludingTheClashingIdsLeavesOnlyAWarningAboutTheDanglingRelationship() {
        DryRunReport report = restore("""
                org_ids: [2]
                exclude_ids: [26, 15]
                """);

        assertThat(report.selectedNodes()).isEqualTo(18);
        assertThat(report.nodes().missing()).isEqualTo(8);
        assertThat(report.nodes().same()).isEqualTo(10);
        assertThat(report.nodes().conflict()).isZero();
        assertThat(report.relationships().dangling()).isEqualTo(1);
        assertThat(report.gate()).isEqualTo(Gate.WARN);
    }

    @Test
    void anOrgThatLostNothingIsClear() {
        DryRunReport report = restore("org_ids: [3]");

        assertThat(report.selectedNodes()).isEqualTo(5);
        assertThat(report.nodes().same()).isEqualTo(5);
        assertThat(report.nodes().missing()).isZero();
        assertThat(report.nodes().missingRunCount()).isZero();
        assertThat(report.relationships().total()).isZero();
        assertThat(report.gate()).isEqualTo(Gate.CLEAR);
        assertThat(report.gateReasons()).isEmpty();
    }

    @Test
    void labelAndIdRangeAreCombinedWithAnd() {
        DryRunReport report = restore("""
                labels: [Service]
                id_ranges: [[0, 12]]
                """);

        assertThat(report.selectedNodes()).isEqualTo(13);
        assertThat(report.nodes().missing()).isEqualTo(4);
        assertThat(report.nodes().same()).isEqualTo(9);
        assertThat(report.relationships().total()).isEqualTo(4);
        assertThat(report.relationships().dangling()).isEqualTo(4);
    }

    @Test
    void idsAndRangesFormOneOrGroupThatIsAndedWithTheLabel() {
        DryRunReport report = restore("""
                labels: [Booking]
                ids: [30]
                id_ranges: [[20, 22]]
                """);

        assertThat(report.selectedNodes()).isEqualTo(4);
        assertThat(report.nodes().missing()).isEqualTo(3);
        assertThat(report.nodes().same()).isEqualTo(1);
        assertThat(report.relationships().total()).isEqualTo(4);
        assertThat(report.relationships().dangling()).isEqualTo(4);
    }

    @Test
    void splittingWithoutATargetReportsRelationshipsThatLeaveTheSelection() {
        DryRunReport report = service.run(backup.client(), null, SelectionConfigParser.parse("org_ids: [2]"),
                DryRunOptions.defaults(), DryRunProgress.NONE);

        assertThat(report.targetVersion()).isNull();
        assertThat(report.nodes()).isNull();
        assertThat(report.selectedNodes()).isEqualTo(20);
        var rels = report.relationships();
        assertThat(rels.scope()).isEqualTo("selected-nodes");
        assertThat(rels.total()).isEqualTo(11);
        assertThat(rels.internal()).isEqualTo(10);
        assertThat(rels.boundary()).isEqualTo(1);
        assertThat(rels.boundarySamples()).singleElement().satisfies(s -> {
            assertThat(s.startNode()).isEqualTo(20);
            assertThat(s.endNode()).isZero();
        });
        assertThat(report.gate()).isEqualTo(Gate.WARN);
    }

    @Test
    void relationshipAnalysisCanBeSwitchedOff() {
        DryRunReport report = service.run(backup.client(), live.client(), SelectionConfigParser.parse("org_ids: [2]"),
                new DryRunOptions(100, 20, false), DryRunProgress.NONE);

        assertThat(report.relationships()).isNull();
        assertThat(report.nodes().missing()).isEqualTo(8);
    }

    @Test
    void sampleSizeLimitsHowManyExamplesAreKept() {
        DryRunReport report = service.run(backup.client(), live.client(), SelectionConfigParser.parse("org_ids: [2]"),
                new DryRunOptions(100, 3, true), DryRunProgress.NONE);

        assertThat(report.nodes().missing()).isEqualTo(8);
        assertThat(report.nodes().missingSamples()).hasSize(3);
        assertThat(report.nodes().conflictSamples()).hasSize(2);
    }

    @Test
    void selectingEverythingIsFlaggedAndStillComparesAllNodes() {
        DryRunReport report = service.run(backup.client(), live.client(), SelectionConfig.everything(),
                DryRunOptions.defaults(), DryRunProgress.NONE);

        assertThat(report.selectsEverything()).isTrue();
        assertThat(report.selectedNodes()).isEqualTo(35);
        assertThat(report.nodes().missing()).isEqualTo(9);
        assertThat(report.nodes().conflict()).isEqualTo(2);
        assertThat(report.nodes().same()).isEqualTo(24);
    }

    @Test
    void reportSurvivesAJsonRoundTrip() {
        DryRunReport report = restore("org_ids: [2]");

        DryRunReport again = com.etolv.neo4jtool.engine.Json.read(com.etolv.neo4jtool.engine.Json.write(report),
                DryRunReport.class);

        assertThat(again.selectedNodes()).isEqualTo(report.selectedNodes());
        assertThat(again.nodes().missing()).isEqualTo(report.nodes().missing());
        assertThat(again.gate()).isEqualTo(Gate.BLOCKED);
        assertThat(again.selection().orgIds()).containsExactly(2L);
    }
}
