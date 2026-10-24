package com.etolv.neo4jtool.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.BuildOptions;
import com.etolv.neo4jtool.engine.build.MergeOptions;
import com.etolv.neo4jtool.engine.build.MergeResult;
import com.etolv.neo4jtool.engine.build.MergeService;
import com.etolv.neo4jtool.engine.build.MergeTarget;
import com.etolv.neo4jtool.engine.build.RenumberOptions;
import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.fast.ReaderProcess;
import com.etolv.neo4jtool.engine.instance.DockerRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceSettings;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * Nodes of the company whose id the target gives to another node are restored under new ids, and every property of the
 * company's nodes that held such an id follows. The expected database is worked out here, from the source and the target,
 * by a separate (and plainer) implementation of the rules; the merge must produce exactly that, by Bolt and with the fast
 * reader alike.
 *
 * <p>Source: 200 nodes (ids 0-199) of company 2, of which company 1 owns customers 10, 12, 14, 16, 122 and bookings
 * 150-159. Target: the same, but company 1 lost everything except customer 16, its customers 10 and 12 are now suppliers of
 * company 2 (the id is taken), and it has a booking of its own that the source never had.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenumberMergeIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final long COMPANY = 1;

    private static final List<String> SEED = List.of(
            "UNWIND range(0, 199) AS i CREATE (:Filler {org_id: 2, k: i})",
            "MATCH (n) WHERE id(n) IN [10, 12, 14, 16, 122] SET n:Customer, n.org_id = 1, n.name = 'customer ' + id(n) REMOVE n:Filler, n.k",
            "MATCH (n) WHERE id(n) >= 150 AND id(n) <= 159 SET n:Booking, n.org_id = 1, n.ref = 'B' + id(n) REMOVE n:Filler, n.k",
            "MATCH (n) WHERE id(n) = 150 SET n.customer = 10",
            "MATCH (n) WHERE id(n) = 151 SET n.customer = '10'",
            "MATCH (n) WHERE id(n) = 152 SET n.customer = 12",
            "MATCH (n) WHERE id(n) = 153 SET n.customer = 122",
            "MATCH (n) WHERE id(n) = 154 SET n.customer = 1012, n.other = 110, n.padded = '010', n.f = 10.0, n.spaced = ' 10', n.wide = 100",
            "MATCH (n) WHERE id(n) = 155 SET n.customers = [10, 12, 5], n.names = ['10', 'x']",
            "MATCH (n) WHERE id(n) = 156 SET n.customer = 14",
            "MATCH (n) WHERE id(n) = 157 SET n.note = 'plain'",
            "MATCH (n) WHERE id(n) = 158 SET n.customer = 12, n.legacy = 10",
            "MATCH (n) WHERE id(n) = 159 SET n.texts = ['12', '120', 'x'], n.ints = [12, 13], n.count = 3",
            "MATCH (n) WHERE id(n) = 16 SET n.friend = 12",
            "MATCH (n) WHERE id(n) = 20 SET n.x = 10, n.y = '12'",
            "UNWIND [[150, 10], [152, 12], [153, 122], [155, 10], [16, 10], [10, 12], [10, 16], [12, 30], [122, 14], [158, 12]] AS p "
                    + "MATCH (a) WHERE id(a) = p[0] MATCH (b) WHERE id(b) = p[1] CREATE (a)-[:REL {seq: p[0], to_id: p[1]}]->(b)",
            "CREATE CONSTRAINT customer_name FOR (c:Customer) REQUIRE c.name IS UNIQUE");

    /** Ids 11 and 13 are free in both databases. */
    private static final String GAPS = "MATCH (n) WHERE id(n) IN [11, 13] DETACH DELETE n";

    /**
     * Made before anything is deleted, so that they get ids 200 and 201: Neo4j hands a freed id to a node created
     * afterwards, which would make this fixture depend on timing.
     */
    private static final List<String> TARGET_NEW = List.of(
            "CREATE (:Booking {org_id: 1, ref: 'newer', customer: 10, customers: [12]})",
            "CREATE (:Filler {org_id: 2, k: -1, x: 10})");

    private static final List<String> TARGET_LOST = List.of(
            "MATCH (n) WHERE n.org_id = 1 AND NOT id(n) IN [10, 12, 16] AND id(n) < 200 DETACH DELETE n",
            "MATCH (n) WHERE id(n) IN [10, 12] SET n:Supplier, n.org_id = 2, n.name = 'supplier ' + id(n) REMOVE n:Customer");

    @TempDir
    static Path temp;

    private ReaderProcess source;
    private Neo4jClient sourceBolt;
    private ReaderProcess.Settings readerSettings;
    private Path targetDump;
    private InstanceRunner runner;
    private Path work;
    private Graph sourceGraph;
    private Graph targetGraph;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void start() throws Exception {
        work = Files.createDirectories(temp.resolve("work"));
        runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m", "128m",
                Duration.ofMinutes(5)));
        List<String> sourceStatements = new ArrayList<>(SEED);
        sourceStatements.add(GAPS);
        Path sourceData = EmbeddedFixture.create(temp.resolve("source-home"), sourceStatements.toArray(new String[0]), Map.of());
        List<String> target = new ArrayList<>(SEED);
        target.addAll(TARGET_NEW);
        target.add(GAPS);
        target.addAll(TARGET_LOST);
        Path targetData = EmbeddedFixture.create(temp.resolve("target-home"), target.toArray(new String[0]), Map.of());
        targetDump = runner.dumpStoppedDatabase(targetData, "neo4j", Files.createDirectories(temp.resolve("dumps-in")), log::add);

        readerSettings = new ReaderProcess.Settings(System.getProperty("java.class.path"), "1g", 256L * 1024 * 1024,
                temp.resolve("scratch"), Duration.ofMinutes(3));
        source = ReaderProcess.start(readerSettings, sourceData, "neo4j", true, log::add);
        sourceBolt = new Neo4jClient(new ConnectionSettings(source.boltUri(), null, null, "neo4j"));
        sourceBolt.verify();
        sourceGraph = Graph.read(sourceBolt);
        targetGraph = graphOfDump(targetDump);
    }

    @AfterAll
    void stop() {
        if (sourceBolt != null) {
            sourceBolt.close();
        }
        if (source != null) {
            source.close();
        }
    }

    @Test
    void theFixtureIsWhatTheScenarioNeeds() {
        long newer = targetGraph.nodes.entrySet().stream().filter(e -> e.getValue().props.get("ref") != null
                && e.getValue().props.get("ref").equals("newer")).mapToLong(Map.Entry::getKey).findFirst().orElseThrow();
        assertThat(newer).as("the target's own booking must have an id the source does not have").isGreaterThanOrEqualTo(200);
        assertThat(sourceGraph.nodes.keySet()).doesNotContain(11L, 13L);
        assertThat(targetGraph.nodes.get(10L).labels).containsExactly("Supplier");
        assertThat(sourceGraph.nodes.get(10L).labels).containsExactly("Customer");
    }

    @Test
    void withTheOptionOffAClashStillStopsTheMerge() {
        assertThatThrownBy(() -> merge("off", true, RenumberOptions.off()))
                .isInstanceOf(BuildException.class).hasMessageContaining("used by a different node");
    }

    @Test
    void boltAndTheFastReaderGiveTheSameNewDatabaseAndItIsTheExpectedOne() throws Exception {
        RenumberOptions options = RenumberOptions.on();
        Run bolt = merge("bolt", false, options);
        Run fast = merge("fast", true, options);

        assertAsExpected(bolt, options);
        assertAsExpected(fast, options);
        assertThat(fast.graph.nodes).as("fast and Bolt nodes").isEqualTo(bolt.graph.nodes);
        assertThat(fast.graph.relationships).as("fast and Bolt relationships").containsExactlyInAnyOrderElementsOf(bolt.graph.relationships);
        assertThat(fast.result.renumberedNodes()).isEqualTo(bolt.result.renumberedNodes());
        assertThat(fast.result.rewrittenValues()).isEqualTo(bolt.result.rewrittenValues());
        assertThat(fast.result.rewrittenNodes()).isEqualTo(bolt.result.rewrittenNodes());

        // the plain facts, written out by hand, so that the model above cannot share a mistake with the code
        Map<Long, Node> n = fast.graph.nodes;
        long first = 202;                                                   // the target's own nodes have ids 200 and 201
        assertThat(fast.result.renumberedNodes()).isEqualTo(2);
        assertThat(n.get(first).props).containsEntry("name", "customer 10");
        assertThat(n.get(first + 1).props).containsEntry("name", "customer 12");
        assertThat(n.get(10L).labels).as("the other node keeps its id").containsExactly("Supplier");
        assertThat(n.get(150L).props.get("customer")).isEqualTo(first);
        assertThat(n.get(151L).props.get("customer")).isEqualTo(Long.toString(first));
        assertThat(n.get(152L).props.get("customer")).isEqualTo(first + 1);
        assertThat(n.get(153L).props.get("customer")).as("122 is not 12").isEqualTo(122L);
        assertThat(n.get(154L).props).containsEntry("customer", 1012L).containsEntry("other", 110L).containsEntry("padded", "010")
                .containsEntry("f", 10.0).containsEntry("spaced", " 10").containsEntry("wide", 100L);
        assertThat(n.get(155L).props.get("customers")).isEqualTo(List.of(first, first + 1, 5L));
        assertThat(n.get(155L).props.get("names")).isEqualTo(List.of(Long.toString(first), "x"));
        assertThat(n.get(156L).props.get("customer")).isEqualTo(14L);
        assertThat(n.get(157L).props).containsEntry("note", "plain");
        assertThat(n.get(159L).props.get("texts")).isEqualTo(List.of(Long.toString(first + 1), "120", "x"));
        assertThat(n.get(159L).props.get("ints")).isEqualTo(List.of(first + 1, 13L));
        assertThat(n.get(16L).props.get("friend")).as("a company node already on the target").isEqualTo(first + 1);
        assertThat(n.get(20L).props).as("another company").containsEntry("x", 10L).containsEntry("y", "12");
        Node newer = n.get(200L);
        assertThat(newer.props).as("made after the backup: left alone").containsEntry("customer", 10L);
        assertThat(newer.props.get("customers")).isEqualTo(List.of(12L));
        assertThat(n.get(150L).props.get("org_id")).isEqualTo(COMPANY);
        assertThat(fast.graph.relationships).contains("150->" + first + " REL {seq=150, to_id=10}",
                "152->" + (first + 1) + " REL {seq=152, to_id=12}", "153->122 REL {seq=153, to_id=122}",
                "16->" + first + " REL {seq=16, to_id=10}", first + "->" + (first + 1) + " REL {seq=10, to_id=12}",
                (first + 1) + "->30 REL {seq=12, to_id=30}");
        assertThat(Files.readAllLines(Path.of(fast.result.idMappingFile()))).containsExactly("old_id,new_id,labels,org",
                "10," + first + ",Customer,1", "12," + (first + 1) + ",Customer,1");
    }

    @Test
    void gapsNewerNodesAndExcludedPropertiesAreHonouredWithTheFastReader() throws Exception {
        RenumberOptions options = new RenumberOptions(true, RenumberOptions.Allocation.FILL_GAPS, true, true, Set.of("legacy"));
        Run run = merge("gaps", true, options);

        assertAsExpected(run, options);
        Map<Long, Node> n = run.graph.nodes;
        assertThat(n.get(11L).props).as("the smallest free ids").containsEntry("name", "customer 10");
        assertThat(n.get(13L).props).containsEntry("name", "customer 12");
        assertThat(n.get(150L).props.get("customer")).isEqualTo(11L);
        assertThat(n.get(158L).props).containsEntry("customer", 13L).containsEntry("legacy", 10L);
        assertThat(n.get(200L).props.get("customer")).as("newer nodes included").isEqualTo(11L);
        assertThat(n.get(200L).props.get("customers")).isEqualTo(List.of(13L));
    }

    @Test
    void listsCanBeLeftAlone() throws Exception {
        RenumberOptions options = new RenumberOptions(true, RenumberOptions.Allocation.ABOVE_HIGHEST, false, false, Set.of());
        Run run = merge("nolists", false, options);

        assertAsExpected(run, options);
        assertThat(run.graph.nodes.get(155L).props.get("customers")).isEqualTo(List.of(10L, 12L, 5L));
        assertThat(run.graph.nodes.get(150L).props.get("customer")).isEqualTo(202L);
    }

    @Test
    void theDryRunListsTheNewIdsAndThePropertiesThatHoldTheOldOnes() throws Exception {
        var selection = SelectionConfigParser.parse("org_ids: [" + COMPANY + "]");
        var options = new com.etolv.neo4jtool.engine.dryrun.DryRunOptions(100, 20, true).withRenumber(RenumberOptions.on(), 2);
        try (var instance = runner.startFromDump(targetDump, "neo4j", log::add);
             Neo4jClient targetBolt = new Neo4jClient(new ConnectionSettings(instance.boltUri(), null, null, "neo4j"))) {
            var viaBolt = new com.etolv.neo4jtool.engine.dryrun.DryRunService().run(sourceBolt, targetBolt, selection, options,
                    DryRunProgress.NONE);
            var viaFast = new com.etolv.neo4jtool.engine.dryrun.DryRunService().run(sourceBolt, source, targetBolt, null, selection,
                    options, DryRunProgress.NONE);
            var blocked = new com.etolv.neo4jtool.engine.dryrun.DryRunService().run(sourceBolt, targetBolt, selection,
                    new com.etolv.neo4jtool.engine.dryrun.DryRunOptions(100, 20, true), DryRunProgress.NONE);

            assertThat(blocked.gate().name()).as("without the option an id that is taken blocks").isEqualTo("BLOCKED");
            assertThat(blocked.renumbering()).isNull();
            for (var report : List.of(viaBolt, viaFast)) {
                assertThat(report.gate().name()).isEqualTo("WARN");
                var plan = report.renumbering();
                assertThat(plan.nodes()).isEqualTo(2);
                assertThat(plan.mapping()).extracting("oldId", "newId").containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, 202L), org.assertj.core.groups.Tuple.tuple(12L, 203L));
                assertThat(plan.mapping().get(0).targetLabels()).containsExactly("Supplier");
                var refs = plan.references();
                assertThat(refs.moved().nodesScanned()).isEqualTo(14);
                assertThat(refs.moved().values()).isEqualTo(9);
                assertThat(refs.moved().nodesWithReferences()).isEqualTo(6);
                assertThat(refs.existing().values()).isEqualTo(1);
                assertThat(refs.existing().groups()).extracting("property").containsExactly("friend");
                assertThat(refs.newer().values()).isEqualTo(2);
                assertThat(refs.newer().nodesWithReferences()).isEqualTo(1);
                assertThat(refs.moved().groups()).extracting("property").contains("customer", "customers", "names", "texts", "ints", "legacy");
                assertThat(refs.moved().groups().stream().filter(g -> g.property().equals("customer")).findFirst().orElseThrow().examples())
                        .extracting("before").contains("10", "12");
                assertThat(report.relationships().conflict()).as("the relationships of the moved nodes are internal now").isZero();
            }
            assertThat(viaFast.renumbering().references()).isEqualTo(viaBolt.renumbering().references());
            assertThat(viaFast.renumbering().mapping()).isEqualTo(viaBolt.renumbering().mapping());
        }
    }

    // ---- running the merge ----

    private record Run(MergeResult result, Graph graph) {
    }

    private Run merge(String name, boolean fast, RenumberOptions renumber) throws Exception {
        var selection = SelectionConfigParser.parse("org_ids: [" + COMPANY + "]");
        Path output = temp.resolve(name + ".dump");
        BuildOptions build = fast ? new BuildOptions(100, true, true, 3, 0, "", ReaderProcess.factory(readerSettings))
                : new BuildOptions(100, true, true, 1, 0, "", null);
        MergeResult result = new MergeService().run(sourceBolt, fast ? source : null, selection, MergeTarget.dump(targetDump, "neo4j"),
                runner, work, temp.resolve("backups"), output, new MergeOptions(build, false, renumber), DryRunProgress.NONE, log::add);
        return new Run(result, graphOfDump(output));
    }

    private Graph graphOfDump(Path dump) {
        try (var instance = runner.startFromDump(dump, "neo4j", log::add);
             Neo4jClient client = new Neo4jClient(new ConnectionSettings(instance.boltUri(), null, null, "neo4j"))) {
            return Graph.read(client);
        }
    }

    // ---- what the new database must be, worked out plainly ----

    private void assertAsExpected(Run run, RenumberOptions options) {
        Graph s = sourceGraph;
        Graph t = targetGraph;
        List<Long> selected = s.nodes.entrySet().stream().filter(e -> Long.valueOf(COMPANY).equals(e.getValue().props.get("org_id")))
                .map(Map.Entry::getKey).toList();
        List<Long> clash = new ArrayList<>();
        List<Long> moved = new ArrayList<>();
        for (long id : selected) {
            Node there = t.nodes.get(id);
            if (there == null) {
                moved.add(id);
            } else if (!there.identity().equals(s.nodes.get(id).identity())) {
                clash.add(id);
                moved.add(id);
            }
        }
        Map<Long, Long> remap = new TreeMap<>();
        if (options.allocation() == RenumberOptions.Allocation.ABOVE_HIGHEST) {
            long next = Math.max(t.nodes.keySet().stream().mapToLong(Long::longValue).max().orElseThrow(),
                    s.nodes.keySet().stream().mapToLong(Long::longValue).max().orElseThrow()) + 1;
            for (long id : clash) {
                remap.put(id, next++);
            }
        } else {
            Set<Long> used = new java.util.HashSet<>(t.nodes.keySet());
            moved.stream().filter(id -> !clash.contains(id)).forEach(used::add);
            long candidate = 0;
            for (long id : clash) {
                while (used.contains(candidate)) {
                    candidate++;
                }
                remap.put(id, candidate++);
            }
        }

        long[] counts = new long[2];                                  // nodes with a change, properties changed
        Map<Long, Node> expected = new TreeMap<>();
        t.nodes.forEach((id, node) -> {
            boolean company = Long.valueOf(COMPANY).equals(node.props.get("org_id"));
            expected.put(id, company && (options.includeNewerNodes() || s.nodes.containsKey(id)) ? rewritten(node, remap, options, counts) : node);
        });
        for (long id : moved) {
            expected.put(remap.getOrDefault(id, id), rewritten(s.nodes.get(id), remap, options, counts));
        }
        List<String> expectedRelationships = new ArrayList<>(t.relationships);
        long restored = 0;
        for (Rel r : s.rels) {
            if (!moved.contains(r.a) && !moved.contains(r.b)) {
                continue;
            }
            if (known(r.a, moved, s, t) && known(r.b, moved, s, t)) {
                expectedRelationships.add(remap.getOrDefault(r.a, r.a) + "->" + remap.getOrDefault(r.b, r.b) + " " + r.type + " " + r.props);
                restored++;
            }
        }

        assertThat(clash).as("the scenario has clashes").isNotEmpty();
        assertThat(counts[1]).as("the scenario has references to rewrite").isGreaterThan(0);
        assertThat(run.graph.nodes).as("nodes: ids, labels, properties").isEqualTo(expected);
        assertThat(run.graph.relationships).as("relationships").containsExactlyInAnyOrderElementsOf(expectedRelationships);
        assertThat(run.result.renumberedNodes()).isEqualTo(clash.size());
        assertThat(run.result.restoredNodes()).isEqualTo(moved.size());
        assertThat(run.result.alreadyThereNodes()).isEqualTo(selected.size() - moved.size());
        assertThat(run.result.rewrittenNodes()).isEqualTo(counts[0]);
        assertThat(run.result.rewrittenValues()).isEqualTo(counts[1]);
        assertThat(run.result.restoredRelationships()).isEqualTo(restored);
        assertThat(Path.of(run.result.idMappingFile())).isRegularFile();
        assertThat(Files.exists(Path.of(run.result.idMappingFile()))).isTrue();
    }

    private static boolean known(long id, List<Long> moved, Graph s, Graph t) {
        if (moved.contains(id)) {
            return true;
        }
        Node there = t.nodes.get(id);
        return there != null && there.identity().equals(s.nodes.get(id).identity());
    }

    private static Node rewritten(Node node, Map<Long, Long> remap, RenumberOptions options, long[] counts) {
        Map<String, Object> props = new TreeMap<>();
        boolean changed = false;
        for (var p : node.props.entrySet()) {
            Object value = p.getValue();
            if (!p.getKey().equals("org_id") && !options.excluded().contains(p.getKey())) {
                Object replaced = replace(value, remap, options.lists());
                if (!replaced.equals(value)) {
                    counts[1]++;
                    changed = true;
                    value = replaced;
                }
            }
            props.put(p.getKey(), value);
        }
        if (changed) {
            counts[0]++;
        }
        return new Node(node.labels, props);
    }

    private static Object replace(Object value, Map<Long, Long> remap, boolean lists) {
        if (value instanceof Long l) {
            return remap.getOrDefault(l, l);
        }
        if (value instanceof String text) {
            if (text.matches("0|[1-9][0-9]{0,17}") && remap.containsKey(Long.parseLong(text))) {
                return Long.toString(remap.get(Long.parseLong(text)));
            }
            return text;
        }
        if (lists && value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(e -> out.add(replace(e, remap, true)));
            return out;
        }
        return value;
    }

    // ---- reading a database ----

    private record Node(List<String> labels, Map<String, Object> props) {
        String identity() {
            return labels + "|" + props.get("org_id");
        }
    }

    private record Rel(long a, long b, String type, String props) {
    }

    private static final class Graph {
        final Map<Long, Node> nodes = new TreeMap<>();
        final List<Rel> rels = new ArrayList<>();
        final List<String> relationships = new ArrayList<>();

        static Graph read(Neo4jClient client) {
            Graph g = new Graph();
            try (Session session = client.readSession()) {
                for (Record r : session.run("MATCH (n) RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props").list()) {
                    List<String> labels = new ArrayList<>(r.get("labels").asList(v -> v.asString()));
                    labels.sort(String::compareTo);
                    g.nodes.put(r.get("id").asLong(), new Node(labels, new TreeMap<>(r.get("props").asMap())));
                }
                for (Record r : session.run("MATCH (a)-[r]->(b) RETURN id(a) AS a, id(b) AS b, type(r) AS type, properties(r) AS props").list()) {
                    Rel rel = new Rel(r.get("a").asLong(), r.get("b").asLong(), r.get("type").asString(), new TreeMap<>(r.get("props").asMap()).toString());
                    g.rels.add(rel);
                    g.relationships.add(rel.a + "->" + rel.b + " " + rel.type + " " + rel.props);
                }
            }
            return g;
        }
    }
}
