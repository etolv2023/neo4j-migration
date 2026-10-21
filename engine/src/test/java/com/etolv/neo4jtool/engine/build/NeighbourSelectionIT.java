package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Neo4jFixture;
import com.etolv.neo4jtool.engine.dryrun.DryRunOptions;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.LeftOutGroup;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.LeftOutReason;
import com.etolv.neo4jtool.engine.dryrun.DryRunService;
import com.etolv.neo4jtool.engine.instance.DockerRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.InstanceSettings;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
 * Only the invoice carries the company; its lines do not. With {@code include_neighbours} the lines come with the invoice,
 * the nodes of other companies and what is two steps away do not, and what is left out is reported.
 *
 * <pre>
 * 0 Invoice org 1 (A)         1 Invoice org 2 (B)
 * 2 Line, no org  -> A        3 Line, no org -> A        4 Line, org '' -> A
 * 5 Line, org 2   -> A        6 Line, no org -> A and -> B (shared)      7 Line, no org -> B
 * 8 Product, no org: lines 2 and 7 point to it            9 Country, org 9 (shared company): A points to it
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NeighbourSelectionIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    private Neo4jFixture source;
    private Path root;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createSource() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "neighbour-it-");
        source = Neo4jFixture.start();
        seed(source);
    }

    private static void seed(Neo4jFixture db) {
        for (String node : List.of("(:Invoice {org_id: 1})", "(:Invoice {org_id: 2})", "(:Line {n: 1})", "(:Line {n: 2})",
                "(:Line {org_id: ''})", "(:Line {org_id: 2})", "(:Line {n: 5})", "(:Line {n: 6})", "(:Product {name: 'p'})",
                "(:Country {org_id: 9})")) {
            db.run("CREATE " + node);
        }
        for (int[] edge : new int[][] {{2, 0}, {3, 0}, {4, 0}, {5, 0}, {6, 0}, {6, 1}, {7, 1}}) {
            db.run("MATCH (a) WHERE id(a) = $a MATCH (b) WHERE id(b) = $b CREATE (a)-[:PART_OF]->(b)",
                    java.util.Map.of("a", edge[0], "b", edge[1]));
        }
        db.run("MATCH (a) WHERE id(a) IN [2, 7] MATCH (p:Product) CREATE (a)-[:OF]->(p)");
        db.run("MATCH (a) WHERE id(a) = 0 MATCH (c:Country) CREATE (a)-[:IN]->(c)");
    }

    @AfterAll
    void cleanUp() {
        source.close();
        TestDocker.run("run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w", IMAGE, "-c",
                "rm -rf /w/" + root.getFileName());
    }

    private List<Long> selected(String yaml) {
        CypherQuery query = SelectionQueryBuilder.idStream(SelectionConfigParser.parse(yaml));
        try (Session session = source.client().readSession()) {
            return session.run(query.text(), query.params()).list(r -> r.get("id").asLong()).stream().sorted().toList();
        }
    }

    @Test
    void withoutTheOptionOnlyTheInvoiceIsSelected() {
        assertThat(selected("org_ids: [1]")).containsExactly(0L);
    }

    @Test
    void linesWithNoOrganisationOrAnEmptyOneComeWithTheInvoiceButAnotherCompanysLineDoesNot() {
        assertThat(selected("org_ids: [1]\ninclude_neighbours: true")).containsExactly(0L, 2L, 3L, 4L, 6L);
    }

    @Test
    void aSharedCompanyChosenTogetherIsSelectedByItsOwnId() {
        assertThat(selected("org_ids: [1, 9]\ninclude_neighbours: true")).containsExactly(0L, 2L, 3L, 4L, 6L, 9L);
    }

    @Test
    void theLabelFilterKeepsItsMeaningAndAnExcludedIdStaysOut() {
        assertThat(selected("org_ids: [1]\nlabels: [Invoice]\ninclude_neighbours: true")).containsExactly(0L, 2L, 3L, 4L, 6L);
        assertThat(selected("org_ids: [1]\nexclude_ids: [3]\ninclude_neighbours: true")).containsExactly(0L, 2L, 4L, 6L);
    }

    @Test
    void theNodesAddedAreCountedByLabel() {
        CypherQuery with = SelectionQueryBuilder.labelCounts(SelectionConfigParser.parse("org_ids: [1]\ninclude_neighbours: true"));
        try (Session session = source.client().readSession()) {
            var counts = session.run(with.text(), with.params()).list(r -> r.get("label").asString() + "=" + r.get("c").asLong());
            assertThat(counts).containsExactlyInAnyOrder("Invoice=1", "Line=4");
        }
        CypherQuery stats = SelectionQueryBuilder.nodeStats(SelectionConfigParser.parse("org_ids: [1]\ninclude_neighbours: true"));
        try (Session session = source.client().readSession()) {
            var row = session.run(stats.text(), stats.params()).single();
            assertThat(row.get("c").asLong()).isEqualTo(5);
            assertThat(row.get("maxId").asLong()).isEqualTo(6);
        }
    }

    @Test
    void theReportListsWhatIsLeftOutAndWhy() {
        DryRunReport report = new DryRunService().run(source.client(), null,
                SelectionConfigParser.parse("org_ids: [1]\ninclude_neighbours: true"), DryRunOptions.defaults(), DryRunProgress.NONE);

        assertThat(report.selectedNodes()).isEqualTo(5);
        List<LeftOutGroup> groups = report.relationships().leftOut();
        // Line 2 -> Product has no organisation and is two steps away; line 6 -> B and line 5 -> A lead to the company 2; A -> Country to 9.
        assertThat(groups).extracting(g -> g.reason() + " " + g.fromLabels() + " " + g.type() + " " + g.toLabels() + " " + g.count() + " " + g.otherOrgs())
                .containsExactlyInAnyOrder(
                        LeftOutReason.OTHER_ORGANISATION + " [Line] PART_OF [Invoice] 2 [2]",
                        LeftOutReason.NO_ORGANISATION + " [Line] OF [Product] 1 []",
                        LeftOutReason.OTHER_ORGANISATION + " [Invoice] IN [Country] 1 [9]");
        assertThat(groups).allSatisfy(g -> assertThat(g.samples()).isNotEmpty());
        assertThat(report.relationships().boundary()).isEqualTo(4);
    }

    @Test
    void aNodeWithoutAnOrganisationIsTheSameNodeOnTheTargetWhenLabelsMatchAndItsLossIsFound() {
        try (Neo4jFixture target = Neo4jFixture.start()) {
            seed(target);
            target.run("MATCH (n) WHERE id(n) IN [2, 6] DETACH DELETE n");

            DryRunReport report = new DryRunService().run(source.client(), target.client(),
                    SelectionConfigParser.parse("org_ids: [1]\ninclude_neighbours: true"), DryRunOptions.defaults(), DryRunProgress.NONE);

            assertThat(report.nodes().missing()).isEqualTo(2);
            assertThat(report.nodes().same()).isEqualTo(3);
            assertThat(report.nodes().conflict()).isZero();
            assertThat(report.gate()).isNotEqualTo(DryRunReport.Gate.BLOCKED);
        }
    }

    @Test
    void aSharedCompanyMovedTogetherLeavesItsRelationshipInsideTheMove() {
        DryRunReport report = new DryRunService().run(source.client(), null,
                SelectionConfigParser.parse("org_ids: [1, 9]\ninclude_neighbours: true"), DryRunOptions.defaults(), DryRunProgress.NONE);

        assertThat(report.relationships().leftOut()).extracting(LeftOutGroup::type).containsExactlyInAnyOrder("PART_OF", "OF");
        assertThat(report.relationships().internal()).isEqualTo(5);
    }

    @Test
    void aSplitCopiesTheInvoiceItsLinesAndTheSharedCompanyWithTheirRelationships() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        InstanceRunner runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "512m",
                "128m", Duration.ofMinutes(5)));
        Path dump = root.resolve("split.dump");

        SplitResult result = new SplitService().run(source.client(), SelectionConfigParser.parse("org_ids: [1, 9]\ninclude_neighbours: true"),
                runner, work, dump, new BuildOptions(100, true, false), DryRunProgress.NONE, log::add);

        assertThat(result.nodes()).isEqualTo(6);
        assertThat(result.relationships()).isEqualTo(5);
        try (RunningInstance instance = runner.startFromDump(dump, "neo4j", log::add);
             Driver driver = GraphDatabase.driver(instance.boltUri(), AuthTokens.none());
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            GraphCompare.Graph built = GraphCompare.read(session);
            assertThat(built.nodes().keySet()).containsExactlyInAnyOrder(0L, 2L, 3L, 4L, 6L, 9L);
            assertThat(built.relationships()).hasSize(5);
        }
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).isEmpty();
        }
    }
}
