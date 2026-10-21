package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.instance.DockerRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.InstanceSettings;
import com.etolv.neo4jtool.engine.instance.LocalRunner;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * Splits a source with awkward data into a dump, loads the dump and compares every node and every relationship
 * between the selected nodes with the source: the ids, labels, property names, types and values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitServiceIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    private Neo4jFixture source;
    private Path root;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSource() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "split-it-");
        source = Neo4jFixture.start();
        // Filler first, so that the nodes under test start at an id above zero; some of it is deleted again.
        source.run("UNWIND range(1, 40) AS i CREATE (:Filler {n: i})");
        source.run("MATCH (n:Filler) WHERE n.n % 3 = 0 DELETE n");

        source.run("""
                CREATE (a:Service:Active {org_id: 1, name: 'plain', code: 'S-1', count: 5, ratio: 0.25, flag: true})
                CREATE (b:Service {org_id: 1, name: 'multi\nline\r\nwith "quotes", commas; and ünïcödé ✓', code: 'S-2',
                                   empty: '', blank: ' ', count: -9223372036854775807, ratio: -1.5e300})
                CREATE (c:Service {org_id: 1, name: 'dates', code: 'S-3',
                                   day: date('2024-02-29'), at: time('10:15:30+02:00'), local: localtime('23:59:59.123456789'),
                                   stamp: datetime('2024-05-01T12:00:00+03:00'), localStamp: localdatetime('2024-05-01T12:00:00.5'),
                                   zoned: datetime({year: 2024, month: 5, day: 1, hour: 12, timezone: 'Africa/Cairo'}),
                                   span: duration({months: 14, days: 3, seconds: 7, nanoseconds: 500}),
                                   negSpan: duration({days: -2, seconds: -3}),
                                   where2d: point({x: 1.5, y: 2.5}), where3d: point({x: 1, y: 2, z: 3}),
                                   geo: point({longitude: 31.2, latitude: 30.0})})
                CREATE (d:Service {org_id: 1, name: 'lists', code: 'S-4',
                                   tags: ['a', 'b', 'c d'], numbers: [1, 2, 3], reals: [1.5, 2.5], flags: [true, false],
                                   noTags: [], days: [date('2024-01-01'), date('2024-01-02')],
                                   raw: $bytes})
                CREATE (e:Service {org_id: 1, name: 'mixed-1', code: 'S-5', size: 10})
                CREATE (f:Service {org_id: 1, name: 'mixed-2', code: 'S-6', size: 'ten'})
                CREATE (g:Booking {org_id: 1, code: 'B-1', note: 'x'})
                CREATE (h:Booking {org_id: 1, code: 'B-2'})
                CREATE (i:Booking {org_id: 2, code: 'B-3'})
                CREATE (j:Service {org_id: 2, name: 'other company', code: 'S-7'})
                CREATE (k:Orphan {name: 'no org'})
                CREATE (a)-[:HAS {since: date('2020-01-01'), note: 'line\nbreak', weights: [1, 2]}]->(g)
                CREATE (a)-[:HAS]->(h)
                CREATE (a)-[:HAS]->(h)
                CREATE (g)-[:FOR {rank: 1}]->(b)
                CREATE (b)-[:SELF]->(b)
                CREATE (c)-[:LINKS]->(j)
                CREATE (j)-[:LINKS]->(c)
                CREATE (i)-[:HAS]->(j)
                CREATE (k)-[:LINKS]->(a)
                """, Map.of("bytes", new byte[] {0, 1, 2, (byte) 255, 10, 13, 44, 34}));
        source.run("CREATE INDEX service_name FOR (n:Service) ON (n.name)");
        source.run("CREATE CONSTRAINT booking_code FOR (n:Booking) REQUIRE n.code IS UNIQUE");
        source.run("CALL db.awaitIndexes(60)");
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
                IMAGE, "-c", "rm -rf /w/" + root.getFileName());
    }

    @Test
    void dockerSplitGivesADumpWhoseNodesAndRelationshipsMatchTheSourceExactly() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-docker"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        SplitResult result = split(runner, work, root.resolve("docker.dump"), "org_ids: [1]");

        assertMatches(runner, result, root.resolve("docker.dump"));
        assertThat(result.nodes()).isEqualTo(8);
        assertThat(result.relationships()).isEqualTo(5);
        assertThat(result.relationshipsToOutside()).isEqualTo(1);
        assertThat(result.propertiesWrittenAfter()).isGreaterThanOrEqualTo(2);
        assertThat(result.schemaStatements()).isEqualTo(2);
        assertThat(result.consistencyChecked()).isTrue();
        assertThat(log).as("the check's warning for every unused id is counted, not logged")
                .noneMatch(l -> l.contains("is not in use but the id is not up for reuse"))
                .anyMatch(l -> l.contains("unused node slots were reported by the check"));
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).as("work directory after the split").isEmpty();
        }
        assertThat(TestDocker.run("ps", "-a", "--filter", "name=neo4j-migration-", "--format", "{{.Names}}"))
                .as("containers left behind").isEmpty();
    }

    @Test
    void localSplitDoesTheSame() throws Exception {
        Path home = root.resolve("neo4j-home");
        String container = "split-it-copy-" + System.nanoTime();
        TestDocker.run("create", "--name", container, IMAGE);
        try {
            TestDocker.run("cp", container + ":/var/lib/neo4j", home.toString());
        } finally {
            TestDocker.run("rm", container);
        }
        Path work = Files.createDirectories(root.resolve("work-local"));
        InstanceRunner runner = new LocalRunner(settings(InstanceSettings.Runner.LOCAL, work, home));

        SplitResult result = split(runner, work, root.resolve("local.dump"), "labels: [Service]\nexclude_ids: [999999]");

        assertMatches(runner, result, root.resolve("local.dump"));
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).as("work directory after the split").isEmpty();
        }
    }

    @Test
    void severalWorkersGiveTheSameDatabaseAndTheSameFingerprintsAsOne() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-workers"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));
        SplitResult one = split(runner, work, root.resolve("one-worker.dump"), "org_ids: [1]", 1);
        SplitResult four = split(runner, work, root.resolve("four-workers.dump"), "org_ids: [1]", 4);

        assertMatches(runner, four, root.resolve("four-workers.dump"));
        assertThat(four.nodes()).isEqualTo(one.nodes());
        assertThat(four.relationships()).isEqualTo(one.relationships());
        assertThat(four.relationshipsToOutside()).isEqualTo(one.relationshipsToOutside());
        assertThat(four.propertiesWrittenAfter()).isEqualTo(one.propertiesWrittenAfter());
        assertThat(four.nodeFingerprint()).isEqualTo(one.nodeFingerprint());
        assertThat(four.relationshipFingerprint()).isEqualTo(one.relationshipFingerprint());
    }

    @Test
    void anExistingOutputFileIsNeverOverwrittenAndNothingIsStarted() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-exists"));
        Path existing = Files.writeString(root.resolve("exists.dump"), "keep me");
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        assertThatThrownBy(() -> split(runner, work, existing, "org_ids: [1]"))
                .isInstanceOf(BuildException.class).hasMessageContaining("already exists");
        assertThat(existing).hasContent("keep me");
    }

    @Test
    void aSelectionThatMatchesNothingStopsBeforeAnythingIsBuilt() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-empty"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        assertThatThrownBy(() -> split(runner, work, root.resolve("empty.dump"), "org_ids: [12345]"))
                .isInstanceOf(BuildException.class).hasMessageContaining("matches no node");
        assertThat(root.resolve("empty.dump")).doesNotExist();
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    private SplitResult split(InstanceRunner runner, Path work, Path output, String selectionYaml) {
        return split(runner, work, output, selectionYaml, 1);
    }

    private SplitResult split(InstanceRunner runner, Path work, Path output, String selectionYaml, int workers) {
        return new SplitService().run(source.client(), SelectionConfigParser.parse(selectionYaml), runner, work, output,
                new BuildOptions(100, true, true).withWorkers(workers), DryRunProgress.NONE, log::add);
    }

    /** Loads the dump and compares it, node by node and relationship by relationship, with the source. */
    private void assertMatches(InstanceRunner runner, SplitResult result, Path dump) throws Exception {
        assertThat(dump).isRegularFile();
        GraphCompare.Graph built;
        try (RunningInstance instance = runner.startFromDump(dump, "neo4j", log::add);
             Driver driver = GraphDatabase.driver(instance.boltUri(), AuthTokens.none());
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            built = GraphCompare.read(session);
        }
        assertThat(built.nodes()).hasSize((int) result.nodes());
        GraphCompare.Graph original;
        try (Session session = source.client().readSession()) {
            original = GraphCompare.read(session);
        }
        // The split holds the selected nodes and the relationships between them.
        Map<Long, GraphCompare.Node> expectedNodes = new TreeMap<>();
        built.nodes().keySet().forEach(id -> expectedNodes.put(id, original.nodes().get(id)));
        assertThat(built.nodes()).as("nodes with ids, labels and properties").isEqualTo(expectedNodes);
        List<String> expectedRelationships = new ArrayList<>();
        try (Session session = source.client().readSession()) {
            for (Record r : session.run("UNWIND $ids AS i MATCH (a) WHERE id(a) = i MATCH (a)-[r]->(b) WHERE id(b) IN $ids "
                    + "RETURN id(a) AS a, id(b) AS b, type(r) AS type, properties(r) AS props",
                    Map.of("ids", new ArrayList<>(built.nodes().keySet()))).list()) {
                expectedRelationships.add(GraphCompare.relationship(r.get("a").asLong(), r.get("b").asLong(), r.get("type").asString(),
                        GraphCompare.describe(r.get("props"))));
            }
        }
        assertThat(built.relationships()).as("relationships between the selected nodes")
                .containsExactlyInAnyOrderElementsOf(expectedRelationships).hasSize((int) result.relationships());
        assertThat(built.schema()).as("indexes and constraints").isEqualTo(original.schema());
    }

    private static InstanceSettings settings(InstanceSettings.Runner runner, Path work, Path home) {
        return new InstanceSettings(runner, IMAGE, home, work, "512m", "128m", Duration.ofMinutes(5));
    }
}
