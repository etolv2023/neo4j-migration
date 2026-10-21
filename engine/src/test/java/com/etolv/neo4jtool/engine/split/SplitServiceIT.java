package com.etolv.neo4jtool.engine.split;

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
import java.util.Arrays;
import java.util.Base64;
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
import org.neo4j.driver.Value;

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
        docker("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
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
        assertThat(docker("ps", "-a", "--filter", "name=neo4j-migration-", "--format", "{{.Names}}"))
                .as("containers left behind").isEmpty();
    }

    @Test
    void localSplitDoesTheSame() throws Exception {
        Path home = root.resolve("neo4j-home");
        String container = "split-it-copy-" + System.nanoTime();
        docker("create", "--name", container, IMAGE);
        try {
            docker("cp", container + ":/var/lib/neo4j", home.toString());
        } finally {
            docker("rm", container);
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
    void anExistingOutputFileIsNeverOverwrittenAndNothingIsStarted() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-exists"));
        Path existing = Files.writeString(root.resolve("exists.dump"), "keep me");
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        assertThatThrownBy(() -> split(runner, work, existing, "org_ids: [1]"))
                .isInstanceOf(SplitException.class).hasMessageContaining("already exists");
        assertThat(existing).hasContent("keep me");
    }

    @Test
    void aSelectionThatMatchesNothingStopsBeforeAnythingIsBuilt() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-empty"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        assertThatThrownBy(() -> split(runner, work, root.resolve("empty.dump"), "org_ids: [12345]"))
                .isInstanceOf(SplitException.class).hasMessageContaining("matches no node");
        assertThat(root.resolve("empty.dump")).doesNotExist();
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    private SplitResult split(InstanceRunner runner, Path work, Path output, String selectionYaml) {
        return new SplitService().run(source.client(), SelectionConfigParser.parse(selectionYaml), runner, work, output,
                new SplitOptions(100, true, true), DryRunProgress.NONE, log::add);
    }

    /** Loads the dump and compares it, node by node and relationship by relationship, with the source. */
    private void assertMatches(InstanceRunner runner, SplitResult result, Path dump) throws Exception {
        assertThat(dump).isRegularFile();
        Map<Long, Object> expectedNodes = new TreeMap<>();
        Map<Long, Object> actualNodes = new TreeMap<>();
        List<String> expectedRels;
        List<String> actualRels;
        List<String> expectedSchema;
        List<String> actualSchema;
        // The comparison set is what the source holds for the nodes that are in the dump.
        try (RunningInstance instance = runner.startFromDump(dump, "neo4j", log::add);
             Driver built = GraphDatabase.driver(instance.boltUri(), AuthTokens.none());
             Session builtSession = built.session(SessionConfig.forDatabase("neo4j"))) {
            nodes(builtSession, actualNodes);
            List<Long> ids = new ArrayList<>(actualNodes.keySet());
            assertThat(ids).hasSize((int) result.nodes());
            try (Session sourceSession = source.client().readSession()) {
                nodesById(sourceSession, ids, expectedNodes);
                expectedRels = relationships(sourceSession, ids);
            }
            actualRels = relationships(builtSession, ids);
            actualSchema = schema(builtSession);
            try (Session sourceSession = source.client().readSession()) {
                expectedSchema = schema(sourceSession);
            }
        }
        assertThat(actualNodes).as("nodes with ids, labels and properties").isEqualTo(expectedNodes);
        assertThat(actualRels).as("relationships between the selected nodes").containsExactlyInAnyOrderElementsOf(expectedRels);
        assertThat(actualRels).hasSize((int) result.relationships());
        assertThat(actualSchema).as("indexes and constraints").isEqualTo(expectedSchema);
    }

    private static void nodes(Session session, Map<Long, Object> into) {
        for (Record r : session.run("MATCH (n) RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props").list()) {
            into.put(r.get("id").asLong(), describe(r));
        }
    }

    private static void nodesById(Session session, List<Long> ids, Map<Long, Object> into) {
        for (Record r : session.run("UNWIND $ids AS i MATCH (n) WHERE id(n) = i RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props",
                Map.of("ids", ids)).list()) {
            into.put(r.get("id").asLong(), describe(r));
        }
    }

    private static Object describe(Record r) {
        List<String> labels = new ArrayList<>(r.get("labels").asList(Value::asString));
        labels.sort(String::compareTo);
        Map<String, Object> props = new TreeMap<>();
        r.get("props").asMap().forEach((k, v) -> props.put(k, normalize(v)));
        return labels + " " + props;
    }

    /** Relationship ids are not kept, so a relationship is identified by its ends, type and properties. */
    private static List<String> relationships(Session session, List<Long> ids) {
        List<String> rows = new ArrayList<>();
        for (Record r : session.run("""
                UNWIND $ids AS i MATCH (a) WHERE id(a) = i MATCH (a)-[r]->(b) WHERE id(b) IN $ids
                RETURN id(a) AS a, id(b) AS b, type(r) AS type, properties(r) AS props""", Map.of("ids", ids)).list()) {
            Map<String, Object> props = new TreeMap<>();
            r.get("props").asMap().forEach((k, v) -> props.put(k, normalize(v)));
            rows.add(r.get("a").asLong() + "->" + r.get("b").asLong() + " " + r.get("type").asString() + " " + props);
        }
        return rows;
    }

    private static List<String> schema(Session session) {
        List<String> rows = new ArrayList<>();
        rows.addAll(session.run("SHOW CONSTRAINTS YIELD name, type, labelsOrTypes, properties RETURN name, type, labelsOrTypes, properties").list(SplitServiceIT::schemaRow));
        rows.addAll(session.run("SHOW INDEXES YIELD name, type, labelsOrTypes, properties, owningConstraint "
                + "WHERE type <> 'LOOKUP' AND owningConstraint IS NULL RETURN name, type, labelsOrTypes, properties").list(SplitServiceIT::schemaRow));
        rows.sort(String::compareTo);
        return rows;
    }

    private static String schemaRow(Record r) {
        return r.get("name").asString() + " " + r.get("type").asString() + " " + r.get("labelsOrTypes").asList() + " "
                + r.get("properties").asList();
    }

    private static Object normalize(Object value) {
        if (value instanceof byte[] bytes) {
            return "bytes:" + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SplitServiceIT::normalize).toList();
        }
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(SplitServiceIT::normalize).toList();
        }
        return value;
    }

    private static String docker(String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return output;
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InstanceSettings settings(InstanceSettings.Runner runner, Path work, Path home) {
        return new InstanceSettings(runner, IMAGE, home, work, "512m", "128m", Duration.ofMinutes(5));
    }
}
