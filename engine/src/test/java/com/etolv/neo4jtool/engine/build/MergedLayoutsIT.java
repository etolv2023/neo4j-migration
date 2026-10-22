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
 * Label sets that differ in their properties share CSV layouts (see {@link LayoutMerger}): the database that comes out of
 * the import must still be the source, node by node: ids, labels, every value, and above all a property a node does not
 * have must stay absent (an empty cell is no property), an empty text must stay an empty text, and a property with different
 * types in different label sets must keep each type.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergedLayoutsIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final int LABEL_SETS = Integer.getInteger("layouts.labelSets", 240);
    private static final int KEYS = 30;

    private Neo4jFixture source;
    private Path root;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSource() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "merged-layouts-it-");
        source = Neo4jFixture.start();
        StringBuilder cypher = new StringBuilder();
        for (int i = 1; i <= LABEL_SETS; i++) {
            // two nodes per label set with different subsets of the 30 shared properties (even ones are text, odd ones numbers)
            for (int node = 0; node < 2; node++) {
                cypher.append("CREATE (:Many:L").append(i).append(" {org_id: 1");
                for (int key = 0; key < KEYS; key++) {
                    if (((i * 7 + key * 3 + node) % 5) < 2) {
                        cypher.append(", k").append(key).append(": ").append(key % 2 == 0 ? "'t" + i + "_" + key + "_" + node + "'" : String.valueOf(i * 100 + key + node));
                    }
                }
                // one property has a different type in odd and even label sets
                cypher.append(", v: ").append(i % 2 == 0 ? String.valueOf(i) : "'text " + i + "'").append("}) ");
            }
            if (i % 40 == 0) {
                source.run(cypher.toString());
                cypher.setLength(0);
            }
        }
        if (cypher.length() > 0) {
            source.run(cypher.toString());
        }
        // an empty text on one node only: other nodes must not get one; and nothing but the organisation, lists and booleans
        source.run("CREATE (:Special {org_id: 1, s: '', t: 'x'}) CREATE (:Special {org_id: 1, t: 'y'}) CREATE (:Special {org_id: 1}) "
                + "CREATE (:Lists {org_id: 1, tags: ['a', 'b'], flag: true}) CREATE (:Lists {org_id: 1, flag: false}) CREATE (:Lists {org_id: 1, tags: []}) "
                + "CREATE (:Bare {org_id: 1})");
        // one property with several types inside a label set
        source.run("CREATE (:Mixed {org_id: 1, v: 1}) CREATE (:Mixed {org_id: 1, v: 'one'}) CREATE (:Mixed {org_id: 1, v: 1.5}) CREATE (:Mixed {org_id: 1})");
        source.run("MATCH (a:L1), (b:L2), (c:Special {t: 'x'}), (d:Special {t: 'y'}) CREATE (a)-[:NEXT {rank: 1}]->(b) CREATE (b)-[:NEXT {rank: 'two'}]->(c) "
                + "CREATE (c)-[:NEXT]->(d)");
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
                IMAGE, "-c", "rm -rf /w/" + root.getFileName());
    }

    @Test
    void theNewDatabaseIsTheSourceWhetherLayoutsAreSharedOrNot() throws Exception {
        int separate = split("off", 0);
        int shared = split("shared", BuildOptions.DEFAULT_LAYOUT_COLUMNS);
        int narrow = split("narrow", 6);

        // without sharing every label set that differs in its properties is a layout of its own
        assertThat(separate).isGreaterThan(LABEL_SETS / 2);
        // with sharing: the layouts of the two types of 'v', and of the label set with several types, and a few more
        assertThat(shared).isLessThan(12);
        // a narrow limit makes several layouts, still far fewer than without sharing
        assertThat(narrow).isBetween(shared, separate - 1);
    }

    /** @return how many node layouts the export wrote */
    private int split(String name, int layoutColumns) throws Exception {
        Path work = Files.createDirectories(root.resolve(name + "-work"));
        InstanceRunner runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m", "128m",
                Duration.ofMinutes(5)));
        Path dump = root.resolve(name + ".dump");
        List<String> phases = new ArrayList<>();
        DryRunProgress progress = new DryRunProgress() {
            @Override
            public void phase(String phase) {
                phases.add(phase);
            }
        };

        // the split also compares the new database with the export (counts and a hash over every node and relationship)
        SplitResult result = new SplitService().run(source.client(), SelectionConfigParser.parse("org_ids: [1]"), runner, work, dump,
                new BuildOptions(100, false, false).withWorkers(3).withLayoutColumns(layoutColumns), progress, log::add);

        GraphCompare.Graph built;
        try (RunningInstance instance = runner.startFromDump(dump, "neo4j", log::add);
             Driver driver = GraphDatabase.driver(instance.boltUri(), AuthTokens.none());
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            built = GraphCompare.read(session);
        }
        GraphCompare.Graph original;
        try (Session session = source.client().readSession()) {
            original = GraphCompare.read(session);
        }
        assertThat(result.nodes()).isEqualTo(original.nodes().size());
        assertThat(built.nodes()).as(name + ": nodes with ids, labels and every property").isEqualTo(original.nodes());
        assertThat(built.relationships()).as(name + ": relationships").containsExactlyInAnyOrderElementsOf(original.relationships());

        assertThat(phases).anyMatch(p -> p.startsWith(layoutColumns > 0 ? "Export: sharing node layouts" : "Export: node layouts are not shared"));
        Matcher count = Pattern.compile("Export: (\\d+) node layouts").matcher(phases.stream()
                .filter(p -> p.matches("Export: \\d+ node layouts.*")).findFirst().orElseThrow());
        assertThat(count.find()).isTrue();
        return Integer.parseInt(count.group(1));
    }
}
