package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.instance.DockerRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.InstanceSettings;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * A database with far more different layouts (label sets, property type combinations) than the files a worker may keep
 * open, written by several workers: the export must not stop, and the import of the many header and part files must give
 * every node back with its labels and values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManyLayoutsIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final int LABEL_SETS = 700;

    private Neo4jFixture source;
    private Path root;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSource() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "layouts-it-");
        source = Neo4jFixture.start();
        // every node has its own second label, so every node is a layout of its own; two nodes of each also differ in the
        // type of one property
        StringBuilder cypher = new StringBuilder();
        for (int i = 1; i <= LABEL_SETS; i++) {
            cypher.append("CREATE (:Many:L").append(i).append(" {org_id: 1, n: ").append(i).append(", note: 'x").append(i).append("'}) ");
            if (i % 100 == 0) {
                source.run(cypher.toString());
                cypher.setLength(0);
            }
        }
        source.run("CREATE (:Mixed {org_id: 1, v: 1}) CREATE (:Mixed {org_id: 1, v: 'one'}) CREATE (:Mixed {org_id: 1, v: 1.5}) "
                + "CREATE (:Mixed {org_id: 1})");
        source.run("MATCH (a:L1), (b:L2), (c:Mixed {v: 1}) CREATE (a)-[:NEXT {rank: 1}]->(b) CREATE (b)-[:NEXT {rank: 'two'}]->(c)");
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
                IMAGE, "-c", "rm -rf /w/" + root.getFileName());
    }

    @Test
    void moreLayoutsThanOpenFilesAreExportedImportedAndComparedByTheSplit() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        InstanceRunner runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m", "128m",
                Duration.ofMinutes(5)));
        Path dump = root.resolve("many.dump");

        // the split compares the new database with the export (ids, counts, a hash over every node and relationship)
        SplitResult result = new SplitService().run(source.client(), SelectionConfigParser.parse("org_ids: [1]"), runner, work, dump,
                new BuildOptions(500, false, false).withWorkers(3), DryRunProgress.NONE, log::add);

        assertThat(result.nodes()).isEqualTo(LABEL_SETS + 4);
        assertThat(result.relationships()).isEqualTo(2);
        try (RunningInstance instance = runner.startFromDump(dump, "neo4j", log::add);
             Driver driver = GraphDatabase.driver(instance.boltUri(), AuthTokens.none());
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            assertThat(session.run("MATCH (n:Many) RETURN count(DISTINCT labels(n)) AS c").single().get("c").asLong()).isEqualTo(LABEL_SETS);
            assertThat(session.run("MATCH (n:Many) WHERE n.note = 'x' + n.n RETURN count(n) AS c").single().get("c").asLong())
                    .isEqualTo(LABEL_SETS);
            assertThat(session.run("MATCH (n:Mixed) RETURN count(n) AS c").single().get("c").asLong()).isEqualTo(4);
            assertThat(session.run("MATCH (n:Mixed) WHERE n.v = 'one' RETURN count(n) AS c").single().get("c").asLong()).isEqualTo(1);
            assertThat(session.run("MATCH ()-[r:NEXT]->() WHERE r.rank = 'two' RETURN count(r) AS c").single().get("c").asLong()).isEqualTo(1);
        }
    }
}
