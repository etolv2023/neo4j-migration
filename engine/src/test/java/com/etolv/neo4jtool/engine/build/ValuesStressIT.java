package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.instance.DockerRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
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

/**
 * The awkward values a real database holds (dates in the hour a clock is turned back, empty and long lists, huge texts,
 * odd numbers, property names and labels with punctuation) go through the whole split. The split compares the new database
 * with the export, node by node in one hash, so any value that changes on the way fails it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValuesStressIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    /** One Cypher expression per node; each becomes the property {@code val} of its own node. */
    private static final List<String> VALUES = List.of(
            "datetime({year: 2025, month: 10, day: 30, hour: 23, minute: 30, timezone: 'Africa/Cairo'})",
            "datetime('2025-10-30T23:30:00+03:00[Africa/Cairo]')",
            "datetime('2025-10-30T23:30:00+02:00[Africa/Cairo]')",
            "datetime('2025-04-25T00:30:00[Africa/Cairo]')",
            "datetime('1900-01-01T00:00:00[Europe/Paris]')",
            "datetime({year: 2024, month: 1, day: 1, timezone: 'UTC'})",
            "datetime('2024-01-01T10:00:00.123456789+05:30')",
            "datetime('2024-01-01T10:00:00Z')",
            "datetime('9999-12-31T23:59:59.999999999+14:00')",
            "localdatetime('1969-12-31T23:59:59.999999999')",
            "date('0001-01-01')", "date('9999-12-31')", "date('-0044-03-15')",
            "time('12:30:00.123456789+02:00')", "time('00:00:00Z')", "localtime('23:59:59.999999999')", "localtime('10:15')",
            "duration({days: -3, seconds: -4, nanoseconds: -5})", "duration('P1Y2M3DT4H5M6.7S')", "duration({seconds: 0})",
            "point({x: 1.5, y: 2.5})", "point({longitude: 31.2, latitude: 30.0, height: 10})", "point({x: 1, y: 2, z: 3, crs: 'cartesian-3d'})",
            "[]", "['']", "['', 'a']", "['a', '']", "['a', '', 'b']", "[' a ', 'b ']", "['\\n', 'x']", "[' ']", "['a,b', 'c;d', 'e\"f', 'g\\nh']", "range(1, 1000)", "[x IN range(1, 300) | 'item number ' + x]",
            "[true, false, true]", "[1.5, 2.5, -0.0]", "[date('2024-01-01'), date('2024-02-02')]", "[duration({days: 1}), duration({days: 2})]",
            "[datetime('2024-01-01T10:00:00+02:00'), datetime('2024-01-02T10:00:00+02:00')]",
            "[datetime({year: 2024, month: 5, day: 1, timezone: 'Africa/Cairo'})]",
            "[point({x: 1, y: 2}), point({x: 3, y: 4})]", "[localtime('10:00'), localtime('11:00')]",
            "0.0 / 0.0", "1.0 / 0.0", "-1.0 / 0.0", "-0.0", "4.9E-324", "1.7976931348623157E308", "9223372036854775807", "-9223372036854775808",
            "''", "' '", "'a\\r\\nb'", "'\\tx\\t'", "'say \"hi\"'", "'مرحبا 😀'", "'null'", "'00123'",
            "reduce(s = '', i IN range(1, 5000) | s + 'long text segment ')");

    private static final int FROM = Integer.getInteger("values.from", 0);
    private static final int TO = Math.min(VALUES.size(), Integer.getInteger("values.to", VALUES.size()));
    private static final boolean ODD = Boolean.parseBoolean(System.getProperty("values.odd", "true"));

    private Neo4jFixture source;
    private Path root;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSource() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "values-it-");
        source = Neo4jFixture.start();
        for (int i = FROM; i < TO; i++) {
            source.run("CREATE (:V {org_id: 1, idx: " + i + ", val: " + VALUES.get(i) + "})");
        }
        if (!ODD) {
            source.run("CREATE (:Bare {org_id: 1})");
            return;
        }
        // names that are not safe as CSV column names, labels with punctuation, nodes with nothing
        source.run("CREATE (:Odd {org_id: 1, `a:b`: 1, `c(d)`: 'x', ` lead`: 2, `trail `: 3, `with space`: 4, `مفتاح`: 5})");
        source.run("CREATE (:`weird label` {org_id: 1}) CREATE (:`a,b` {org_id: 1}) CREATE (:`عميل`:Odd {org_id: 1}) "
                + "CREATE ({org_id: 1}) CREATE (:Bare {org_id: 1})");
        source.run("MATCH (a:Bare), (b:Odd) WHERE b.`a:b` = 1 CREATE (a)-[:LINK {when: datetime('2025-10-30T23:30:00+02:00'), "
                + "n: 1}]->(b)");
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
                IMAGE, "-c", "rm -rf /w/" + root.getFileName());
    }

    @Test
    void awkwardValuesSurviveTheSplitAndTheComparison() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        InstanceRunner runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m", "128m",
                Duration.ofMinutes(5)));
        Path dump = root.resolve("values.dump");

        SplitResult result = new SplitService().run(source.client(), SelectionConfigParser.parse("org_ids: [1]"), runner, work, dump,
                new BuildOptions(200, false, false).withWorkers(2), DryRunProgress.NONE, log::add);

        assertThat(result.nodes()).isEqualTo(TO - FROM + (ODD ? 6 : 1));
    }
}
