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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * The backup of a database (the source) and a damaged copy of it (the target, a stopped data directory) are merged: the
 * damaged target must come out with everything it had plus the lost nodes, ids kept. The result is compared, node by
 * node and relationship by relationship, with what that should be, worked out here from both databases.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergeServiceIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final String DATABASE = "sales";
    private static final String SELECTION = "org_ids: [1]";

    // The same statements on both databases give the same ids. Customers are ids 0-29, bookings 30-59.
    private static final List<String> SEED = List.of(
            "UNWIND range(1, 30) AS i CREATE (:Customer {org_id: 1 + i % 3, name: 'customer ' + i, note: 'line\\nbreak, \"quoted\" ' + i, "
                    + "tags: [], since: datetime({year: 2024, month: 5, day: 1, hour: 12, timezone: 'Africa/Cairo'})})",
            "UNWIND range(1, 30) AS i CREATE (:Booking {org_id: 1 + i % 3, ref: 'B' + i, price: i * 1.5})",
            "MATCH (b:Booking) WITH b, id(b) AS bid MATCH (c:Customer) WHERE id(c) = bid - 29 "
                    + "CREATE (b)-[:FOR {since: date('2024-01-01'), seq: bid}]->(c)",
            "CREATE CONSTRAINT customer_name FOR (c:Customer) REQUIRE c.name IS UNIQUE",
            "CREATE INDEX booking_org FOR (b:Booking) ON (b.org_id)");

    private Neo4jFixture source;
    private Path root;
    private Path seed;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSourceAndTarget() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "merge-it-");
        source = Neo4jFixture.start();
        for (String statement : SEED) {
            source.run(statement);
        }

        seed = Files.createDirectories(root.resolve("seed"));
        Files.setPosixFilePermissions(seed, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        String name = "merge-it-seed-" + System.nanoTime();
        TestDocker.run("run", "-d", "--name", name, "-e", "NEO4J_AUTH=none", "-e", "NEO4J_initial_dbms_default__database=" + DATABASE,
                "-e", "NEO4J_server_memory_heap_max__size=512m", "-e", "NEO4J_server_memory_pagecache_size=128m",
                "-p", "127.0.0.1::7687", "-v", seed.toAbsolutePath() + ":/data", IMAGE);
        try {
            String port = TestDocker.run("port", name, "7687/tcp").lines().findFirst().orElseThrow();
            String uri = "bolt://127.0.0.1:" + port.substring(port.lastIndexOf(':') + 1);
            try (Driver driver = GraphDatabase.driver(uri, AuthTokens.none())) {
                awaitDatabase(driver);
                for (String statement : SEED) {
                    run(driver, statement);
                }
                run(driver, "CREATE (:Customer {org_id: 2, name: 'target only', tags: [], "
                        + "since: datetime({year: 2024, month: 5, day: 1, hour: 12, timezone: 'Africa/Cairo'})})");
                run(driver, "MATCH (n) WHERE n.org_id = 1 DETACH DELETE n");              // the lost data
                run(driver, "MATCH (n) WHERE id(n) = 3 SET n.org_id = 77");               // next to a lost node, but another node now
                run(driver, "MATCH (n) WHERE id(n) = 1 SET n.org_id = 99");               // an id that a selection can clash on
            }
        } finally {
            TestDocker.run("stop", "-t", "120", name);
            TestDocker.run("rm", name);
        }
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w", IMAGE, "-c",
                "rm -rf /w/" + root.getFileName());
    }

    @Test
    void dockerMergeSwapsTheMergedDatabaseInKeepsTheOldOneAndRollbackBringsItBack() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-docker"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        mergeSwapAndRollBack(runner, "docker", true);
    }

    @Test
    void localMergeDoesTheSame() throws Exception {
        Path home = root.resolve("neo4j-home");
        String container = "merge-it-copy-" + System.nanoTime();
        TestDocker.run("create", "--name", container, IMAGE);
        try {
            TestDocker.run("cp", container + ":/var/lib/neo4j", home.toString());
        } finally {
            TestDocker.run("rm", container);
        }
        Path work = Files.createDirectories(root.resolve("work-local"));
        InstanceRunner runner = new LocalRunner(settings(InstanceSettings.Runner.LOCAL, work, home));

        mergeSwapAndRollBack(runner, "local", false);
    }

    @Test
    void aDumpTargetGivesANewDumpAndTheTargetDumpIsNotChanged() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-dump"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));
        Path targetDump = runner.dumpStoppedDatabase(copy("dump-target"), DATABASE, Files.createDirectories(root.resolve("dumps-in")), log::add);
        String before = sha256(targetDump);
        Path output = root.resolve("merged.dump");

        MergeResult result = new MergeService().run(source.client(), SelectionConfigParser.parse(SELECTION),
                MergeTarget.dump(targetDump, DATABASE), runner, work, root.resolve("backups-dump"), output,
                new MergeOptions(new BuildOptions(100, true, true), true), DryRunProgress.NONE, log::add);

        assertThat(sha256(targetDump)).as("the target dump").isEqualTo(before);
        assertThat(result.swapped()).isFalse();
        assertThat(result.outputFile()).isEqualTo(output.toAbsolutePath().toString());
        assertThat(result.backupFile()).isEqualTo(targetDump.toAbsolutePath().toString());
        assertThat(root.resolve("backups-dump")).doesNotExist();            // a dump is its own backup: no copy is made
        GraphCompare.Graph target = graphOfDump(runner, targetDump);
        GraphCompare.Graph merged = graphOfDump(runner, output);
        assertMerged(merged, target, result);
    }

    @Test
    void aClashOrNothingToRestoreStopsTheMergeAndChangesNothing() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-stop"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));
        Path target = copy("stop-target");
        String signature = StoreSignature.of(target, DATABASE);
        Path backups = root.resolve("backups-stop");
        MergeOptions options = new MergeOptions(new BuildOptions(100, true, false), true);

        assertThatThrownBy(() -> new MergeService().run(source.client(), SelectionConfigParser.parse("ids: [1]"),
                MergeTarget.dataDirectory(target, DATABASE), runner, work, backups, null, options, DryRunProgress.NONE, log::add))
                .isInstanceOf(BuildException.class).hasMessageContaining("used by a different node").hasMessageContaining("first is 1");
        assertThatThrownBy(() -> new MergeService().run(source.client(), SelectionConfigParser.parse("ids: [4]"),
                MergeTarget.dataDirectory(target, DATABASE), runner, work, backups, null, options, DryRunProgress.NONE, log::add))
                .isInstanceOf(BuildException.class).hasMessageContaining("Nothing to restore");

        assertThat(StoreSignature.of(target, DATABASE)).as("the target data directory").isEqualTo(signature);
        try (var kept = Files.list(target)) {
            assertThat(kept.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("before-"));
        }
        try (var dumps = Files.list(backups)) {
            assertThat(dumps.toList()).as("a backup is made first and kept").hasSize(2);
        }
        try (var left = Files.list(work)) {
            assertThat(left.toList()).as("work directory").isEmpty();
        }
    }

    // ---- the swap scenario ----

    private void mergeSwapAndRollBack(InstanceRunner runner, String tag, boolean checkBackup) throws Exception {
        Path work = root.resolve("work-" + tag);
        Path target = copy(tag + "-target");
        GraphCompare.Graph before = graphOfDirectory(runner, copy(tag + "-reference"));
        String signature = StoreSignature.of(target, DATABASE);
        Path backups = root.resolve("backups-" + tag);
        MergeService service = new MergeService();

        MergeResult result = service.run(source.client(), SelectionConfigParser.parse(SELECTION),
                MergeTarget.dataDirectory(target, DATABASE), runner, work, backups, null,
                new MergeOptions(new BuildOptions(100, true, true), true), DryRunProgress.NONE, log::add);

        assertThat(result.swapped()).isTrue();
        assertThat(result.outputFile()).isNull();
        assertThat(result.keepName()).startsWith("before-");
        assertThat(target.resolve(result.keepName()).resolve("databases").resolve(DATABASE)).isDirectory();
        assertThat(Path.of(result.backupFile())).isRegularFile().hasParent(backups);
        if (checkBackup) {
            assertThat(graphOfDump(runner, Path.of(result.backupFile()))).as("the backup is the target as it was")
                    .usingRecursiveComparison().isEqualTo(before);
        }
        GraphCompare.Graph merged = graphOfDirectory(runner, copy(tag + "-check", target));
        assertMerged(merged, before, result);
        try (var left = Files.list(work)) {
            assertThat(left.toList()).as("work directory").isEmpty();
        }

        MergeResult rolledBack = service.rollback(result, runner, log::add);

        assertThat(rolledBack.rolledBackAt()).isNotNull();
        assertThat(StoreSignature.of(target, DATABASE)).as("the old database is exactly back").isEqualTo(signature);
        assertThat(target.resolve("rolled-back-" + result.keepName()).resolve("databases").resolve(DATABASE)).isDirectory();
        assertThat(target.resolve(result.keepName())).doesNotExist();
        assertThatThrownBy(() -> service.rollback(rolledBack, runner, log::add)).isInstanceOf(BuildException.class);
    }

    /** What the merged database must be: everything the target had, plus the selected nodes it lacked, with the relationships. */
    private void assertMerged(GraphCompare.Graph merged, GraphCompare.Graph target, MergeResult result) {
        GraphCompare.Graph original;
        try (Session session = source.client().readSession()) {
            original = GraphCompare.read(session);
        }
        Map<Long, GraphCompare.Node> expectedNodes = new TreeMap<>(target.nodes());
        List<Long> restored = new ArrayList<>();
        original.nodes().forEach((id, node) -> {
            if (Long.valueOf(1).equals(node.org()) && !target.nodes().containsKey(id)) {
                restored.add(id);
                expectedNodes.put(id, node);
            }
        });
        // A node next to a restored one counts only if the target has it as the same node.
        List<Long> sameOnTarget = new ArrayList<>();
        original.nodes().forEach((id, node) -> {
            GraphCompare.Node there = target.nodes().get(id);
            if (there != null && there.identity().equals(node.identity())) {
                sameOnTarget.add(id);
            }
        });
        List<String> expectedRelationships = new ArrayList<>(target.relationships());
        long restoredRelationships = 0;
        long dropped = 0;
        try (Session session = source.client().readSession()) {
            for (var r : session.run("MATCH (a)-[r]->(b) RETURN id(a) AS a, id(b) AS b, type(r) AS type, properties(r) AS props").list()) {
                long a = r.get("a").asLong();
                long b = r.get("b").asLong();
                if (!restored.contains(a) && !restored.contains(b)) {
                    continue;
                }
                boolean aKnown = restored.contains(a) || sameOnTarget.contains(a);
                boolean bKnown = restored.contains(b) || sameOnTarget.contains(b);
                if (aKnown && bKnown) {
                    expectedRelationships.add(GraphCompare.relationship(a, b, r.get("type").asString(), GraphCompare.describe(r.get("props"))));
                    restoredRelationships++;
                } else {
                    dropped++;
                }
            }
        }

        assertThat(restored).isNotEmpty();
        assertThat(dropped).as("the test data must have a relationship that is left out").isGreaterThan(0);
        assertThat(merged.nodes()).as("nodes with ids, labels and properties").isEqualTo(expectedNodes);
        assertThat(merged.relationships()).as("relationships").containsExactlyInAnyOrderElementsOf(expectedRelationships);
        assertThat(merged.schema()).as("indexes and constraints of the target").isEqualTo(target.schema());
        assertThat(result.targetNodes()).isEqualTo(target.nodes().size());
        assertThat(result.restoredNodes()).isEqualTo(restored.size());
        assertThat(result.alreadyThereNodes()).isZero();
        assertThat(result.targetRelationships()).isEqualTo(target.relationships().size());
        assertThat(result.restoredRelationships()).isEqualTo(restoredRelationships);
        assertThat(result.droppedRelationships()).isEqualTo(dropped);
        assertThat(result.propertiesWrittenAfter()).as("empty lists and named-zone dates, on both sides").isGreaterThanOrEqualTo(4);
    }

    // ---- helpers ----

    private GraphCompare.Graph graphOfDump(InstanceRunner runner, Path dump) {
        try (RunningInstance instance = runner.startFromDump(dump, DATABASE, log::add)) {
            return read(instance);
        }
    }

    private GraphCompare.Graph graphOfDirectory(InstanceRunner runner, Path dataDirectory) {
        try (RunningInstance instance = runner.serveDataDirectory(dataDirectory, DATABASE, log::add)) {
            return read(instance);
        }
    }

    private static GraphCompare.Graph read(RunningInstance instance) {
        try (Driver driver = GraphDatabase.driver(instance.boltUri(), AuthTokens.none());
             Session session = driver.session(SessionConfig.forDatabase(DATABASE))) {
            return GraphCompare.read(session);
        }
    }

    private Path copy(String name) {
        return copy(name, seed);
    }

    /** A copy of a data directory, made by a container so that the owners stay as they are. */
    private Path copy(String name, Path from) {
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.toAbsolutePath() + ":/w", IMAGE, "-c",
                "cp -a /w/" + from.getFileName() + " /w/" + name);
        return root.resolve(name);
    }

    private static void run(Driver driver, String cypher) {
        try (Session session = driver.session(SessionConfig.forDatabase(DATABASE))) {
            session.run(cypher).consume();
        }
    }

    private static void awaitDatabase(Driver driver) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (true) {
            try (Session session = driver.session(SessionConfig.forDatabase(DATABASE))) {
                session.run("RETURN 1").consume();
                return;
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static InstanceSettings settings(InstanceSettings.Runner runner, Path work, Path home) {
        return new InstanceSettings(runner, IMAGE, home, work, "512m", "128m", Duration.ofMinutes(5));
    }
}
