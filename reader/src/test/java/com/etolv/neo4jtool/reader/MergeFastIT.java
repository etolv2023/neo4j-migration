package com.etolv.neo4jtool.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.build.BuildOptions;
import com.etolv.neo4jtool.engine.build.MergeOptions;
import com.etolv.neo4jtool.engine.build.MergeResult;
import com.etolv.neo4jtool.engine.build.MergeService;
import com.etolv.neo4jtool.engine.build.MergeTarget;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * A merge that reads the source and the target with the fast reader must restore exactly what the Bolt merge restores
 * (and the new database is verified against the exported data in both).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergeFastIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    private static final String[] SEED = {
            "UNWIND range(1, 30) AS i CREATE (:Customer {org_id: 1 + i % 3, name: 'customer ' + i, note: 'line\\nbreak, \"quoted\" ' + i, "
                    + "tags: [], since: datetime({year: 2024, month: 5, day: 1, hour: 12, timezone: 'Africa/Cairo'})})",
            "UNWIND range(1, 30) AS i CREATE (:Booking {org_id: 1 + i % 3, ref: 'B' + i, price: i * 1.5})",
            "MATCH (b:Booking) WITH b, id(b) AS bid MATCH (c:Customer) WHERE id(c) = bid - 29 "
                    + "CREATE (b)-[:FOR {since: date('2024-01-01'), seq: bid}]->(c)",
            "CREATE CONSTRAINT customer_name FOR (c:Customer) REQUIRE c.name IS UNIQUE",
            "CREATE INDEX booking_org FOR (b:Booking) ON (b.org_id)"
    };

    @TempDir
    static Path temp;

    private ReaderProcess source;
    private Neo4jClient sourceBolt;
    private ReaderProcess.Settings readerSettings;
    private Path targetDump;
    private InstanceRunner runner;
    private Path work;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void start() throws Exception {
        work = Files.createDirectories(temp.resolve("work"));
        runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m", "128m",
                Duration.ofMinutes(5)));
        Path sourceData = EmbeddedFixture.create(temp.resolve("source-home"), SEED, Map.of());

        List<String> damaged = new ArrayList<>(List.of(SEED));
        damaged.add("CREATE (:Customer {org_id: 2, name: 'target only', tags: []})");
        damaged.add("MATCH (n) WHERE n.org_id = 1 DETACH DELETE n");
        Path targetData = EmbeddedFixture.create(temp.resolve("target-home"), damaged.toArray(new String[0]), Map.of());
        targetDump = runner.dumpStoppedDatabase(targetData, "neo4j", Files.createDirectories(temp.resolve("dumps-in")), log::add);

        readerSettings = new ReaderProcess.Settings(System.getProperty("java.class.path"), "1g", 256L * 1024 * 1024,
                temp.resolve("scratch"), Duration.ofMinutes(3));
        source = ReaderProcess.start(readerSettings, sourceData, "neo4j", true, log::add);
        sourceBolt = new Neo4jClient(new ConnectionSettings(source.boltUri(), null, null, "neo4j"));
        sourceBolt.verify();
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
    void theFastMergeRestoresTheSameAsTheBoltMerge() throws Exception {
        var selection = SelectionConfigParser.parse("org_ids: [1]");
        MergeResult viaBolt = new MergeService().run(sourceBolt, selection, MergeTarget.dump(targetDump, "neo4j"), runner, work,
                temp.resolve("backups"), temp.resolve("bolt.dump"),
                new MergeOptions(new BuildOptions(100, true, true, 1, 0, "", null), false), DryRunProgress.NONE, log::add);
        MergeResult viaFast = new MergeService().run(sourceBolt, source, selection, MergeTarget.dump(targetDump, "neo4j"), runner,
                work, temp.resolve("backups"), temp.resolve("fast.dump"),
                new MergeOptions(new BuildOptions(100, true, true, 3, 0, "", ReaderProcess.factory(readerSettings)), false),
                DryRunProgress.NONE, log::add);

        assertThat(viaBolt.restoredNodes()).isEqualTo(20);
        assertThat(viaFast.targetNodes()).isEqualTo(viaBolt.targetNodes());
        assertThat(viaFast.restoredNodes()).isEqualTo(viaBolt.restoredNodes());
        assertThat(viaFast.alreadyThereNodes()).isEqualTo(viaBolt.alreadyThereNodes());
        assertThat(viaFast.targetRelationships()).isEqualTo(viaBolt.targetRelationships());
        assertThat(viaFast.restoredRelationships()).isEqualTo(viaBolt.restoredRelationships());
        assertThat(viaFast.droppedRelationships()).isEqualTo(viaBolt.droppedRelationships());
        assertThat(viaFast.propertiesWrittenAfter()).isEqualTo(viaBolt.propertiesWrittenAfter());
        assertThat(log).as("the fast path was really used").anyMatch(l -> l.contains("Loading ") && l.contains("as database"));
        assertThat(log).noneMatch(l -> l.contains("reading it over Bolt instead"));
    }
}
