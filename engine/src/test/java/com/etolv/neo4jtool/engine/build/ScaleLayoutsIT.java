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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * A whole company: thousands of label sets, several properties with different types inside every label set, and many
 * nodes. The size is set with {@code -Dscale.labelSets} and {@code -Dscale.nodesPerSet}; the defaults keep the build fast.
 * The split compares the new database with the export (counts and a hash over every node and relationship), so a layout
 * that lost or changed a value fails here too.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScaleLayoutsIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final int LABEL_SETS = Integer.getInteger("scale.labelSets", 300);
    private static final int NODES_PER_SET = Integer.getInteger("scale.nodesPerSet", 40);

    private Neo4jFixture source;
    private Path root;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSource() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "scale-layouts-it-");
        source = Neo4jFixture.start();
        for (int i = 1; i <= LABEL_SETS; i++) {
            // c0..c3 have up to three types inside every label set (and are missing on some nodes); k<i % 40> and
            // m<i % 25> are the properties of this label set only
            source.run("UNWIND range(1, " + NODES_PER_SET + ") AS x CREATE (:Big:Company:L" + i + " {org_id: 1, name: 'n' + x, "
                    + "c0: CASE (x + " + i + ") % 4 WHEN 0 THEN x WHEN 1 THEN toString(x) WHEN 2 THEN x + 0.5 ELSE null END, "
                    + "c1: CASE (x + " + (i % 5) + ") % 3 WHEN 0 THEN x WHEN 1 THEN toString(x) ELSE null END, "
                    + "c2: CASE (x * 7 + " + i + ") % 5 WHEN 0 THEN x WHEN 1 THEN x > 3 WHEN 2 THEN toString(x) ELSE null END, "
                    + "c3: CASE x % 2 WHEN 0 THEN " + i + " ELSE null END, "
                    + "k" + (i % 40) + ": 'v' + x, m" + (i % 25) + ": x * " + i + "})");
        }
        source.run("MATCH (a:Big) WITH a LIMIT 1 MATCH (b:Big) WHERE b <> a WITH a, b LIMIT 200 CREATE (a)-[:LINK {w: 1}]->(b)");
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
                IMAGE, "-c", "rm -rf /w/" + root.getFileName());
    }

    @Test
    void aWholeCompanyIsSplitIntoAFewLayouts_andTheNewDatabaseIsTheSource() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        InstanceRunner runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "1g", "256m",
                Duration.ofMinutes(10)));
        List<String> phases = new ArrayList<>();
        long started = System.nanoTime();
        DryRunProgress progress = new DryRunProgress() {
            @Override
            public void phase(String phase) {
                String line = (System.nanoTime() - started) / 1_000_000 + " ms  " + phase;
                phases.add(line);
                System.out.println("SCALE " + line);
            }
        };

        SplitResult result = new SplitService().run(source.client(), SelectionConfigParser.parse("org_ids: [1]"), runner, work,
                root.resolve("scale.dump"), new BuildOptions(1000, false, false).withWorkers(4)
                        .withLayoutColumns(BuildOptions.DEFAULT_LAYOUT_COLUMNS), progress, log::add);

        assertThat(result.nodes()).isEqualTo((long) LABEL_SETS * NODES_PER_SET);
        Matcher count = Pattern.compile("Export: (\\d+) node layouts").matcher(phases.stream()
                .filter(p -> p.matches(".*Export: \\d+ node layouts.*")).findFirst().orElseThrow());
        assertThat(count.find()).isTrue();
        // one layout per combination of the types of c0..c2 at most (4 x 3 x 4), and a few for the properties of single label sets
        assertThat(Integer.parseInt(count.group(1))).as("node layouts").isLessThan(60);
        assertThat(phases).anyMatch(p -> p.contains("pass 1b"));
    }
}
