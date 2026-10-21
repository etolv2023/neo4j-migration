package com.etolv.neo4jtool.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.build.BuildOptions;
import com.etolv.neo4jtool.engine.build.SplitResult;
import com.etolv.neo4jtool.engine.build.SplitService;
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
 * A split that reads the source with the fast reader, on several threads, and compares the new database with the fast
 * reader too, must give exactly the database that the plain Bolt split gives: same counts, same fingerprints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitFastIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    @TempDir
    static Path temp;

    private ReaderProcess reader;
    private Neo4jClient bolt;
    private ReaderProcess.Settings readerSettings;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void start() throws Exception {
        Path data = EmbeddedFixture.create(temp.resolve("home"), new String[] {
                "UNWIND range(1, 60) AS i CREATE (:Filler {n: i})",
                "MATCH (n:Filler) WHERE n.n % 4 = 0 DELETE n",
                """
                UNWIND range(1, 300) AS i
                CREATE (:Service {org_id: 1 + i % 3, code: 'S-' + i, name: 'service ' + i, at: datetime('2024-05-01T12:00:00+03:00'),
                                  tags: CASE WHEN i % 7 = 0 THEN [] ELSE ['a', 'b'] END, text: 'line\nbreak, "quoted"; ü'})
                """,
                """
                MATCH (a:Service), (b:Service) WHERE b.code = 'S-' + (toInteger(substring(a.code, 2)) % 300 + 1)
                CREATE (a)-[:NEXT {w: 1.5, d: date('2020-01-01')}]->(b)
                """,
                "CREATE INDEX service_code FOR (n:Service) ON (n.code)"
        }, Map.of());
        readerSettings = new ReaderProcess.Settings(System.getProperty("java.class.path"), "1g", 256L * 1024 * 1024,
                temp.resolve("scratch"), Duration.ofMinutes(3));
        reader = ReaderProcess.start(readerSettings, data, "neo4j", true, log::add);
        bolt = new Neo4jClient(new ConnectionSettings(reader.boltUri(), null, null, "neo4j"));
        bolt.verify();
    }

    @AfterAll
    void stop() {
        if (bolt != null) {
            bolt.close();
        }
        if (reader != null) {
            reader.close();
        }
    }

    @Test
    void theFastSplitGivesTheSameDatabaseAsTheBoltSplit() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        InstanceRunner runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m",
                "128m", Duration.ofMinutes(5)));
        var selection = SelectionConfigParser.parse("org_ids: [1, 2]\nlabels: [Service]");

        SplitResult viaBolt = new SplitService().run(bolt, selection, runner, work, temp.resolve("bolt.dump"),
                new BuildOptions(100, true, true, 1, 0, "", null), DryRunProgress.NONE, log::add);
        SplitResult viaFast = new SplitService().run(bolt, reader, selection, runner, work, temp.resolve("fast.dump"),
                new BuildOptions(100, true, true, 3, 2, "1g", ReaderProcess.factory(readerSettings)), DryRunProgress.NONE, log::add);

        assertThat(viaBolt.nodes()).isEqualTo(200);
        assertThat(viaFast.nodes()).isEqualTo(viaBolt.nodes());
        assertThat(viaFast.relationships()).isEqualTo(viaBolt.relationships());
        assertThat(viaFast.relationshipsToOutside()).isEqualTo(viaBolt.relationshipsToOutside());
        assertThat(viaFast.propertiesWrittenAfter()).isEqualTo(viaBolt.propertiesWrittenAfter());
        assertThat(viaFast.nodeFingerprint()).isEqualTo(viaBolt.nodeFingerprint());
        assertThat(viaFast.relationshipFingerprint()).isEqualTo(viaBolt.relationshipFingerprint());
        assertThat(viaFast.schemaStatements()).isEqualTo(viaBolt.schemaStatements());
        assertThat(temp.resolve("fast.dump")).exists();

        SplitResult asDirectory = new SplitService().run(bolt, reader, selection, runner, work, temp.resolve("fast-dir"),
                new BuildOptions(100, true, false, 2, 0, "", ReaderProcess.factory(readerSettings)).withDirectoryOutput(true),
                DryRunProgress.NONE, log::add);
        assertThat(asDirectory.nodeFingerprint()).isEqualTo(viaBolt.nodeFingerprint());
        assertThat(asDirectory.relationshipFingerprint()).isEqualTo(viaBolt.relationshipFingerprint());
        assertThat(temp.resolve("fast-dir").resolve("databases").resolve("neo4j").resolve("neostore")).exists();
        assertThat(asDirectory.outputBytes()).isGreaterThan(0);
        assertThat(asDirectory.consistencyChecked()).isFalse();
    }
}
