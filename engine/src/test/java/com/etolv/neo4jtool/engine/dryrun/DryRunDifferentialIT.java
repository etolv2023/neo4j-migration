package com.etolv.neo4jtool.engine.dryrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.IdRun;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.LabelCount;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipSample;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipTypeCounts;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.OrgCount;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Random databases and random selections, checked against {@link Oracle}, which answers the same question with plain
 * loops over two complete dumps. Small batches force every code path that crosses a batch boundary.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DryRunDifferentialIT {

    private static final int BASE_NODES = 3000;
    private static final int BASE_RELS = 5000;
    private static final int BACKUP_ONLY_NODES = 300;
    private static final int LIVE_ONLY_NODES = 200;
    private static final String[] LABELS = {"A", "B", "C", "D", "E"};
    private static final String[] REL_TYPES = {"R1", "R2", "R3"};
    private static final int ROUNDS = 8;
    private static final int SELECTIONS_PER_ROUND = 6;
    private static final long SEED = 20260521L;

    private Neo4jFixture backup;
    private Neo4jFixture live;
    private GraphDump backupDump;
    private final DryRunService service = new DryRunService();

    @BeforeAll
    void start() {
        backup = Neo4jFixture.start();
        live = Neo4jFixture.start();
        // Both databases get the same base data in the same order, so the base nodes have the same ids.
        createNodes(backup, new Random(SEED), BASE_NODES);
        createNodes(live, new Random(SEED), BASE_NODES);
        createRels(backup, new Random(SEED + 1), 0, BASE_NODES, BASE_RELS);
        createRels(live, new Random(SEED + 1), 0, BASE_NODES, BASE_RELS);
        // Extra work that exists on one side only. It takes the same ids on both sides, so some ids clash.
        createNodes(backup, new Random(SEED + 2), BACKUP_ONLY_NODES);
        createRels(backup, new Random(SEED + 3), 0, BASE_NODES + BACKUP_ONLY_NODES, 400);
        createNodes(live, new Random(SEED + 4), LIVE_ONLY_NODES);
        createRels(live, new Random(SEED + 5), 0, BASE_NODES + LIVE_ONLY_NODES, 300);
        backupDump = GraphDump.of(backup);
        assertThat(backupDump.nodes).hasSize(BASE_NODES + BACKUP_ONLY_NODES);
        assertThat(backupDump.nodes.keySet()).first().isEqualTo(0L);
        assertThat(backupDump.nodes.keySet()).last().isEqualTo((long) BASE_NODES + BACKUP_ONLY_NODES - 1);
    }

    @AfterAll
    void stop() {
        backup.close();
        live.close();
    }

    @Test
    void engineAgreesWithTheOracleOnRandomSelectionsAndRandomlyDamagedTargets() {
        Random random = new Random(SEED + 100);
        int compared = 0;
        Set<DryRunReport.Gate> gatesSeen = new HashSet<>();
        for (int round = 0; round < ROUNDS; round++) {
            damageLive(random);
            GraphDump liveDump = GraphDump.of(live);
            for (int i = 0; i < SELECTIONS_PER_ROUND; i++) {
                SelectionConfig config = randomSelection(random);
                DryRunOptions options = new DryRunOptions(100, 20, true);

                Oracle withTarget = Oracle.compute(backupDump, liveDump, config);
                DryRunReport report = service.run(backup.client(), live.client(), config, options, DryRunProgress.NONE);
                assertSameAnswer(withTarget, report, config);
                gatesSeen.add(report.gate());

                Oracle noTarget = Oracle.compute(backupDump, null, config);
                DryRunReport splitReport = service.run(backup.client(), null, config, options, DryRunProgress.NONE);
                assertSameAnswer(noTarget, splitReport, config);
                compared += 2;
            }
        }
        assertThat(compared).isEqualTo(ROUNDS * SELECTIONS_PER_ROUND * 2);
        // The random data is only useful if it reaches every outcome.
        assertThat(gatesSeen).contains(DryRunReport.Gate.BLOCKED);
    }

    private void assertSameAnswer(Oracle expected, DryRunReport actual, SelectionConfig config) {
        String context = "selection " + config + " ";
        assertThat(actual.selectedNodes()).as(context + "selected").isEqualTo(expected.selected);
        assertThat(labelMap(actual.selectedByLabel())).as(context + "selected by label").isEqualTo(expected.selectedByLabel);
        assertThat(orgMap(actual.selectedByOrg())).as(context + "selected by org").isEqualTo(expected.selectedByOrg);

        if (actual.nodes() != null) {
            var nodes = actual.nodes();
            assertThat(nodes.missing()).as(context + "missing").isEqualTo(expected.missing);
            assertThat(nodes.same()).as(context + "same").isEqualTo(expected.same);
            assertThat(nodes.conflict()).as(context + "conflict").isEqualTo(expected.conflict);
            assertThat(labelMap(nodes.missingByLabel())).as(context + "missing by label").isEqualTo(expected.missingByLabel);
            assertThat(labelMap(nodes.conflictByLabel())).as(context + "conflict by label").isEqualTo(expected.conflictByLabel);
            assertThat(orgMap(nodes.missingByOrg())).as(context + "missing by org").isEqualTo(expected.missingByOrg);
            assertThat(orgMap(nodes.conflictByOrg())).as(context + "conflict by org").isEqualTo(expected.conflictByOrg);
            assertThat(nodes.missingRunCount()).as(context + "run count").isEqualTo(expected.missingRuns);
            assertThat(nodes.largestMissingRuns()).as(context + "largest runs")
                    .extracting(IdRun::length).containsExactlyElementsOf(expected.topRunLengths);
            for (IdRun run : nodes.largestMissingRuns()) {
                assertThat(run.toId() - run.fromId() + 1).isEqualTo(run.length());
                for (long id = run.fromId(); id <= run.toId(); id++) {
                    assertThat(expected.missingIds).as(context + "run member " + id).contains(id);
                }
            }
            List<Long> firstMissing = expected.missingIds.stream().sorted().limit(20).toList();
            assertThat(nodes.missingSamples()).as(context + "missing samples")
                    .extracting(DryRunReport.NodeSample::id).containsExactlyElementsOf(firstMissing);
            assertThat(nodes.conflictSamples()).hasSize((int) Math.min(20, expected.conflict));
        } else {
            assertThat(expected.missing + expected.same + expected.conflict).isZero();
        }

        var rels = actual.relationships();
        assertThat(rels.total()).as(context + "relationships").isEqualTo(expected.relTotal);
        Map<String, RelCountsView> actualByType = rels.byType().stream()
                .collect(Collectors.toMap(RelationshipTypeCounts::type, RelCountsView::of));
        Map<String, RelCountsView> expectedByType = new HashMap<>();
        expected.relByType.forEach((type, c) ->
                expectedByType.put(type, new RelCountsView(c.internal, c.resolved, c.conflict, c.dangling, c.boundary)));
        assertThat(actualByType).as(context + "relationships by type").isEqualTo(expectedByType);
        assertSamples(rels.danglingSamples(), expected.danglingRelIds, context + "dangling");
        assertSamples(rels.conflictSamples(), expected.conflictRelIds, context + "conflict rel");
        assertSamples(rels.boundarySamples(), expected.boundaryRelIds, context + "boundary");

        assertThat(actual.gate()).as(context + "gate").isEqualTo(expected.gate);
    }

    private record RelCountsView(long internal, long resolved, long conflict, long dangling, long boundary) {
        static RelCountsView of(RelationshipTypeCounts c) {
            return new RelCountsView(c.internal(), c.resolved(), c.conflict(), c.dangling(), c.boundary());
        }
    }

    private static void assertSamples(List<RelationshipSample> samples, Set<Long> expectedIds, String what) {
        assertThat(samples).as(what + " sample count").hasSize(Math.min(20, expectedIds.size()));
        assertThat(samples).as(what + " sample ids").extracting(RelationshipSample::id).allMatch(expectedIds::contains)
                .doesNotHaveDuplicates();
    }

    private static Map<String, Long> labelMap(List<LabelCount> counts) {
        return counts.stream().collect(Collectors.toMap(LabelCount::label, LabelCount::count));
    }

    private static Map<Object, Long> orgMap(List<OrgCount> counts) {
        Map<Object, Long> map = new HashMap<>();
        counts.forEach(c -> map.put(c.org(), c.count()));
        return map;
    }

    // ---- data generation ----

    /** Creates nodes one CREATE clause at a time, so the ids follow the order of the statement. */
    private static void createNodes(Neo4jFixture db, Random random, int count) {
        for (int from = 0; from < count; from += 500) {
            StringBuilder cypher = new StringBuilder("CREATE ");
            int to = Math.min(count, from + 500);
            for (int i = from; i < to; i++) {
                if (i > from) {
                    cypher.append(", ");
                }
                cypher.append(randomNodePattern(random));
            }
            db.run(cypher.toString());
        }
    }

    private static String randomNodePattern(Random random) {
        StringBuilder pattern = new StringBuilder("(");
        int labelCount = 1 + random.nextInt(2);
        Set<String> labels = new HashSet<>();
        while (labels.size() < labelCount) {
            labels.add(LABELS[random.nextInt(LABELS.length)]);
        }
        labels.stream().sorted().forEach(l -> pattern.append(':').append(l));
        if (random.nextInt(10) > 0) {
            pattern.append(" {org_id: ").append(1 + random.nextInt(5)).append('}');
        }
        return pattern.append(')').toString();
    }

    private static void createRels(Neo4jFixture db, Random random, long firstId, long lastIdExclusive, int count) {
        List<List<Object>> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long a = firstId + random.nextInt((int) (lastIdExclusive - firstId));
            long b = random.nextInt(20) == 0 ? a : firstId + random.nextInt((int) (lastIdExclusive - firstId));
            pairs.add(List.of(a, b, REL_TYPES[random.nextInt(REL_TYPES.length)]));
        }
        for (String type : REL_TYPES) {
            List<List<Long>> ofType = pairs.stream().filter(p -> p.get(2).equals(type))
                    .map(p -> List.of((Long) p.get(0), (Long) p.get(1))).toList();
            db.run("UNWIND $pairs AS p MATCH (a) WHERE id(a) = p[0] MATCH (b) WHERE id(b) = p[1] CREATE (a)-[:" + type + "]->(b)",
                    Map.of("pairs", ofType));
        }
    }

    /** Each round does more damage to the live database: deleted nodes, changed labels, changed orgs. */
    private void damageLive(Random random) {
        int limit = BASE_NODES + LIVE_ONLY_NODES;
        List<Long> deletes = randomIds(random, limit, 40);
        live.run("MATCH (n) WHERE id(n) IN $ids DETACH DELETE n", Map.of("ids", deletes));
        // Delete a block of neighbouring ids too, so that missing ids form runs.
        long blockStart = random.nextInt(limit - 60);
        live.run("MATCH (n) WHERE id(n) >= $from AND id(n) < $to DETACH DELETE n",
                Map.of("from", blockStart, "to", blockStart + 5 + random.nextInt(50)));
        live.run("MATCH (n) WHERE id(n) IN $ids SET n:Z", Map.of("ids", randomIds(random, limit, 25)));
        live.run("MATCH (n) WHERE id(n) IN $ids SET n.org_id = 1 + toInteger(rand() * 8)", Map.of("ids", randomIds(random, limit, 25)));
        live.run("MATCH (n) WHERE id(n) IN $ids REMOVE n.org_id", Map.of("ids", randomIds(random, limit, 10)));
    }

    private static List<Long> randomIds(Random random, int limit, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add((long) random.nextInt(limit));
        }
        return ids;
    }

    private static SelectionConfig randomSelection(Random random) {
        List<Object> orgs = new ArrayList<>();
        if (random.nextInt(3) > 0) {
            int n = 1 + random.nextInt(2);
            for (int i = 0; i < n; i++) {
                orgs.add((long) (1 + random.nextInt(7)));
            }
        }
        List<String> labels = new ArrayList<>();
        if (random.nextInt(3) == 0) {
            labels.add(LABELS[random.nextInt(LABELS.length)]);
            if (random.nextBoolean()) {
                labels.add(LABELS[random.nextInt(LABELS.length)]);
            }
        }
        int idSpace = BASE_NODES + BACKUP_ONLY_NODES + 50;
        List<Long> ids = new ArrayList<>();
        List<List<Long>> ranges = new ArrayList<>();
        if (random.nextInt(3) == 0) {
            for (int i = 0, n = 1 + random.nextInt(60); i < n; i++) {
                ids.add((long) random.nextInt(idSpace));
            }
        }
        if (random.nextInt(3) == 0) {
            for (int i = 0, n = 1 + random.nextInt(2); i < n; i++) {
                long from = random.nextInt(idSpace);
                ranges.add(List.of(from, from + random.nextInt(600)));
            }
        }
        List<Long> exclude = new ArrayList<>();
        if (random.nextBoolean()) {
            for (int i = 0, n = random.nextInt(30); i < n; i++) {
                exclude.add((long) random.nextInt(idSpace));
            }
        }
        return new SelectionConfig(null, orgs, labels, ids, ranges, exclude);
    }
}
