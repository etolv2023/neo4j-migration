package com.etolv.neo4jtool.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.CsvExporter;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.build.Verifier;
import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.fast.ReaderProcess;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionConfigParser;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

/**
 * The fast reader and the Bolt reader must give the same answers: the same selected ids, the same CSV rows, the same
 * counts and fingerprints. A store with awkward data is made with the embedded database, closed, and then opened by the
 * reader process, which also serves Bolt, so both readers look at the very same files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FastReaderIT {

    @TempDir
    static Path temp;

    private ReaderProcess reader;
    private Neo4jClient bolt;
    private final List<String> log = new ArrayList<>();

    private static final String[] DATA = {
            "UNWIND range(1, 40) AS i CREATE (:Filler {n: i})",
            "MATCH (n:Filler) WHERE n.n % 3 = 0 DELETE n",
            """
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
            CREATE (fl:Service {org_id: 1.0, name: 'float org', code: 'S-8'})
            CREATE (tx:Service {org_id: '1', name: 'text org', code: 'S-9'})
            CREATE (a)-[:HAS {since: date('2020-01-01'), note: 'line\nbreak', weights: [1, 2]}]->(g)
            CREATE (a)-[:HAS]->(h)
            CREATE (a)-[:HAS]->(h)
            CREATE (g)-[:FOR {rank: 1}]->(b)
            CREATE (b)-[:SELF]->(b)
            CREATE (c)-[:LINKS]->(j)
            CREATE (j)-[:LINKS]->(c)
            CREATE (i)-[:HAS]->(j)
            CREATE (k)-[:LINKS]->(a)
            """,
            """
            CREATE (inv:Invoice {org_id: 1, number: 'I-1'})
            CREATE (other:Invoice {org_id: 2, number: 'I-2'})
            CREATE (l1:Line {name: 'line one'})
            CREATE (l2:Line {org_id: '', name: 'line two'})
            CREATE (l3:Line {org_id: 2, name: 'line of the other company'})
            CREATE (country:Country {name: 'Egypt'})
            CREATE (deep:Deep {name: 'two steps away'})
            CREATE (inv)-[:HAS]->(l1)
            CREATE (inv)-[:HAS]->(l2)
            CREATE (inv)-[:HAS]->(l3)
            CREATE (inv)-[:IN]->(country)
            CREATE (other)-[:IN]->(country)
            CREATE (l1)-[:NEXT]->(deep)
            CREATE (l1)<-[:TOUCHES]-(:Note {text: 'points at a line'})
            """
    };

    @BeforeAll
    void start() throws Exception {
        Path data = EmbeddedFixture.create(temp.resolve("home"), DATA, Map.of("bytes", new byte[] {0, 1, 2, (byte) 255, 10, 13, 44, 34}));
        String classpath = System.getProperty("java.class.path");
        reader = ReaderProcess.start(new ReaderProcess.Settings(classpath, "1g", 256L * 1024 * 1024, temp.resolve("scratch"),
                Duration.ofMinutes(3)), data, "neo4j", true, log::add);
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

    private IdSet viaBolt(SelectionConfig config) {
        CypherQuery query = SelectionQueryBuilder.idStream(config);
        IdSet.Collector ids = new IdSet.Collector();
        try (Session session = bolt.readSession()) {
            for (Record r : session.run(query.text(), query.params()).list()) {
                ids.add(r.get("id").asLong());
            }
        }
        return ids.build();
    }

    @Test
    void theFastReaderSelectsTheSameNodesAsTheCypherQuery() {
        List<String> selections = List.of(
                "org_ids: [1]",
                "org_ids: [1, 2]",
                "org_ids: [\"1\"]",
                "org_ids: [1.0]",
                "org_ids: [99999]",
                "org_ids: [1]\nlabels: [Service]",
                "org_ids: [1]\nlabels: [Service, Booking]\nexclude_ids: [999999]",
                "labels: [Service, Booking]",
                "labels: [NoSuchLabel]",
                "org_property: code\norg_ids: [\"S-1\", \"B-2\"]",
                "org_property: noSuchProperty\norg_ids: [1]",
                "ids: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32]",
                "id_ranges: [[20, 45]]",
                "ids: [41, 42]\nid_ranges: [[50, 60]]",
                "org_ids: [1]\nexclude_ids: [41, 42, 43]",
                "org_ids: [1]\ninclude_neighbours: true",
                "org_ids: [1]\nlabels: [Invoice]\ninclude_neighbours: true",
                "labels: [Line]\ninclude_neighbours: true",
                "org_ids: [1]\ninclude_neighbours: true\nexclude_ids: [999999]",
                "include_neighbours: true",
                "");
        for (String yaml : selections) {
            SelectionConfig config = yaml.isEmpty() ? SelectionConfig.everything() : SelectionConfigParser.parse(yaml);
            for (int workers : new int[] {1, 3}) {
                IdSet fast = reader.select(config, workers, DryRunProgress.NONE);
                IdSet expected = viaBolt(config);
                assertThat(fast.toArray()).as("selection '%s' with %d workers", yaml, workers).isEqualTo(expected.toArray());
            }
        }
        // a few of them must actually have matched something, or the comparison proves little
        assertThat(viaBolt(SelectionConfigParser.parse("org_ids: [1]")).size()).isGreaterThan(8);
        assertThat(viaBolt(SelectionConfigParser.parse("org_ids: [1]\ninclude_neighbours: true")).size())
                .isGreaterThan(viaBolt(SelectionConfigParser.parse("org_ids: [1]")).size());
    }

    @Test
    void allIdsAreAllNodes() {
        IdSet fast = reader.allIds(2, DryRunProgress.NONE);
        IdSet expected = viaBolt(SelectionConfig.everything());
        assertThat(fast.toArray()).isEqualTo(expected.toArray());
        assertThat(fast.size()).isGreaterThan(20);
    }

    @Test
    void theCsvFilesCountsAndFingerprintsAreTheSameAsTheBoltExportsForAnyNumberOfWorkers() throws Exception {
        IdSet ids = viaBolt(SelectionConfigParser.parse("org_ids: [1]"));
        List<CsvExporter.Rule> rules = List.of(CsvExporter.Rule.out(ids));
        Path boltDir = Files.createDirectories(temp.resolve("csv-bolt"));
        CsvExporter.Export expected = new CsvExporter(CsvExporter.bolt(bolt), ids, rules, "", boltDir, 100, 1,
                DryRunProgress.NONE).export();
        assertThat(expected.fixups().size()).isGreaterThanOrEqualTo(2);
        assertThat(expected.relationshipsToOutside()).isGreaterThan(0);

        for (int workers : new int[] {1, 2, 4}) {
            Path dir = Files.createDirectories(temp.resolve("csv-fast-" + workers));
            CsvExporter.Export fast = reader.export(ids, rules, "", dir, 100, workers, 0, DryRunProgress.NONE);
            assertThat(fast.nodes()).isEqualTo(expected.nodes());
            assertThat(fast.relationships()).isEqualTo(expected.relationships());
            assertThat(fast.nodeFingerprint()).as("node fingerprint with %d workers", workers).isEqualTo(expected.nodeFingerprint());
            assertThat(fast.relationshipFingerprint()).isEqualTo(expected.relationshipFingerprint());
            assertThat(fast.relationshipsToOutside()).isEqualTo(expected.relationshipsToOutside());
            assertThat(fast.fixups().size()).isEqualTo(expected.fixups().size());
            assertThat(rows(dir, fast.nodeFiles())).as("node rows").isEqualTo(rows(boltDir, expected.nodeFiles()));
            assertThat(rows(dir, fast.relationshipFiles())).as("relationship rows").isEqualTo(rows(boltDir, expected.relationshipFiles()));
            assertThat(fixupLines(fast)).isEqualTo(fixupLines(expected));
        }
    }

    @Test
    void incomingRulesGiveTheSameRowsToo() throws Exception {
        IdSet missing = viaBolt(SelectionConfigParser.parse("labels: [Booking]"));
        IdSet confirmed = viaBolt(SelectionConfigParser.parse("labels: [Service]"));
        IdSet ends = IdSet.union(missing, confirmed);
        List<CsvExporter.Rule> rules = List.of(CsvExporter.Rule.out(ends), CsvExporter.Rule.in(confirmed));
        Path boltDir = Files.createDirectories(temp.resolve("in-bolt"));
        CsvExporter.Export expected = new CsvExporter(CsvExporter.bolt(bolt), missing, rules, "r-", boltDir, 100, 2,
                DryRunProgress.NONE).export();
        Path dir = Files.createDirectories(temp.resolve("in-fast"));
        CsvExporter.Export fast = reader.export(missing, rules, "r-", dir, 100, 3, 0, DryRunProgress.NONE);
        assertThat(fast.relationships()).isEqualTo(expected.relationships()).isGreaterThan(0);
        assertThat(fast.relationshipFingerprint()).isEqualTo(expected.relationshipFingerprint());
        assertThat(fast.relationshipsToOutside()).isEqualTo(expected.relationshipsToOutside());
        assertThat(rows(dir, fast.relationshipFiles())).isEqualTo(rows(boltDir, expected.relationshipFiles()));
    }

    @Test
    void verifyAcceptsTheRightTotalsAndRefusesOthers() {
        IdSet ids = viaBolt(SelectionConfig.everything());
        // the whole database as it is: export once to get its totals
        CsvExporter.Export all = reader.export(ids, List.of(CsvExporter.Rule.out(ids)), "", temp.resolve("verify").toFile().mkdirs()
                ? temp.resolve("verify") : temp.resolve("verify"), 100, 2, 0, DryRunProgress.NONE);
        Verifier.Expected right = Verifier.Expected.of(ids, all);
        reader.verify(right, 100, 3, DryRunProgress.NONE);

        Verifier.Expected wrongHash = new Verifier.Expected(ids, right.nodes(), right.relationships(), right.nodeFingerprint() + 1,
                right.relationshipFingerprint());
        assertThatThrownBy(() -> reader.verify(wrongHash, 100, 2, DryRunProgress.NONE)).isInstanceOf(BuildException.class)
                .hasMessageContaining("differ");
        Verifier.Expected wrongCount = new Verifier.Expected(ids, right.nodes() + 1, right.relationships(), right.nodeFingerprint(),
                right.relationshipFingerprint());
        assertThatThrownBy(() -> reader.verify(wrongCount, 100, 2, DryRunProgress.NONE)).isInstanceOf(BuildException.class)
                .hasMessageContaining("nodes");
    }

    @Test
    void aStoreThatAServerHasOpenCannotBeOpenedAgain() {
        // the reader process in this test holds the store: a second one on the same files must fail, not read half-way
        Path data = temp.resolve("home").resolve("data");
        assertThatThrownBy(() -> ReaderProcess.start(new ReaderProcess.Settings(System.getProperty("java.class.path"), "512m",
                128L * 1024 * 1024, temp.resolve("scratch2"), Duration.ofMinutes(2)), data, "neo4j", false, log::add))
                .isInstanceOf(BuildException.class);
    }

    // ---- helpers ----

    /** All data rows of the files, sorted. Header lines are left out: each worker's file has its own copy. */
    private static List<String> rows(Path dir, List<String> files) throws IOException {
        List<String> rows = new ArrayList<>();
        for (String file : files) {
            List<String> lines = Files.readAllLines(dir.resolve(file));
            // a header file (-h<n>.csv) holds only the header; the parts (-p<n>-w<worker>.csv) hold only rows
            if (!file.matches(".*-h\\d+\\.csv")) {
                rows.addAll(lines);
            }
        }
        Collections.sort(rows);
        return rows;
    }

    private static List<String> fixupLines(CsvExporter.Export export) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path file : export.fixups().files()) {
            lines.addAll(Files.readAllLines(file));
        }
        Collections.sort(lines);
        return lines;
    }
}
