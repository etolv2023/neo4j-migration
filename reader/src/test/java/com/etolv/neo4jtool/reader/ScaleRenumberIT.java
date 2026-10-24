package com.etolv.neo4jtool.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.build.BuildOptions;
import com.etolv.neo4jtool.engine.build.MergeOptions;
import com.etolv.neo4jtool.engine.build.MergeResult;
import com.etolv.neo4jtool.engine.build.MergeService;
import com.etolv.neo4jtool.engine.build.MergeTarget;
import com.etolv.neo4jtool.engine.build.RenumberOptions;
import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunOptions;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.dryrun.DryRunService;
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
import org.neo4j.driver.Session;

/**
 * What the renumbering costs on a big database, and that it stays right there. {@code -Dscale.renumber.nodes} (a multiple
 * of 1000) sets the size; the default keeps the normal build fast, the real measurements are made with millions.
 *
 * <p>Node {@code i} (its id) is a customer of company 1 when {@code i % 5 == 0}, a booking of company 1 when
 * {@code i % 5 == 1} and a filler of company 2 otherwise. A booking points at a customer by id, in one of four shapes
 * (whole number, text, list, float); a float is not an id and must stay. The target lost all of the company except a tenth
 * of the customers, and in {@code B} 1 customer in 200 now has its id used by a supplier of company 2.
 * {@code A} is the same without that (those ids are free), which is what a merge without renumbering can do.
 *
 * <p>Whatever the size, the new database must satisfy: every booking's {@code customer} still names the customer it points
 * at by relationship, and a surviving customer's {@code friend} names the node it knew.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScaleRenumberIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final int NODES = Integer.getInteger("scale.renumber.nodes", 50_000);
    private static final int WORKERS = Integer.getInteger("scale.renumber.workers", 2);
    private static final int CUSTOMERS = NODES / 5;
    private static final int CLASH = CUSTOMERS / 200;
    private static final int NEWER = NODES / 1000;

    @TempDir
    static Path temp;

    private InstanceRunner runner;
    private Path work;
    private ReaderProcess.Settings readerSettings;
    private Path sourceData;
    private Path targetA;
    private Path targetB;
    private final List<String> log = new ArrayList<>();
    private final List<String> report = new ArrayList<>();

    @BeforeAll
    void create() throws Exception {
        work = Files.createDirectories(temp.resolve("work"));
        runner = new DockerRunner(new InstanceSettings(InstanceSettings.Runner.DOCKER, IMAGE, null, work, "2g", "512m",
                Duration.ofMinutes(20)));
        long t = System.nanoTime();
        List<String> seed = seed();
        sourceData = EmbeddedFixture.create(temp.resolve("source-home"), seed.toArray(new String[0]), Map.of());
        // ids N.. are the company's own new nodes: made before anything is deleted so that no freed id is reused
        List<String> newer = List.of("UNWIND range(1, " + NEWER + ") AS x CREATE (:Booking {org_id: 1, ref: 'newer' + x, "
                + "customer: (x * 5) % " + (CUSTOMERS * 5) + ", custKey: 'newer'})");
        targetA = EmbeddedFixture.create(temp.resolve("a-home"), target(seed, newer, false).toArray(new String[0]), Map.of());
        targetB = EmbeddedFixture.create(temp.resolve("b-home"), target(seed, newer, true).toArray(new String[0]), Map.of());
        note("fixtures made in " + seconds(t) + " s: " + NODES + " nodes, " + CUSTOMERS + " customers, " + CLASH + " ids taken, "
                + NEWER + " newer bookings");
        readerSettings = new ReaderProcess.Settings(System.getProperty("java.class.path"), System.getProperty("scale.renumber.heap", "2g"),
                512L * 1024 * 1024, temp.resolve("scratch"), Duration.ofMinutes(30));
    }

    @AfterAll
    void write() throws Exception {
        Path out = Path.of("target", "scale-renumber-report.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
        report.forEach(l -> System.out.println("SCALE " + l));
    }

    @Test
    void renumberingCostsLittleAndKeepsEveryReference() throws Exception {
        var selection = SelectionConfigParser.parse("org_ids: [1]");
        Path dumpA = runner.dumpStoppedDatabase(targetA, "neo4j", Files.createDirectories(temp.resolve("dumps-a")), log::add);
        Path dumpB = runner.dumpStoppedDatabase(targetB, "neo4j", Files.createDirectories(temp.resolve("dumps-b")), log::add);

        try (ReaderProcess source = ReaderProcess.start(readerSettings, sourceData, "neo4j", true, log::add);
             Neo4jClient sourceBolt = new Neo4jClient(new ConnectionSettings(source.boltUri(), null, null, "neo4j"))) {
            sourceBolt.verify();

            // ---- merge into A: nothing is taken, the option is off ----
            Timed off = merge("A, option off", sourceBolt, source, selection, dumpA, RenumberOptions.off());
            assertThat(off.result.renumberedNodes()).isZero();
            long restoredA = off.result.restoredNodes();

            // ---- dry run and merge into B: the ids are taken, the option is on ----
            Path loadedB = Files.createDirectories(temp.resolve("b-loaded"));
            runner.loadDump(dumpB, "neo4j", loadedB, log::add);
            try (ReaderProcess target = ReaderProcess.start(readerSettings, loadedB, "neo4j", true, log::add);
                 Neo4jClient targetBolt = new Neo4jClient(new ConnectionSettings(target.boltUri(), null, null, "neo4j"))) {
                targetBolt.verify();
                long started = System.nanoTime();
                DryRunReport dry = new DryRunService().run(sourceBolt, source, targetBolt, target, selection,
                        new DryRunOptions(10_000, 20, true).withRenumber(RenumberOptions.on(), WORKERS), phaseLog("dry run B"));
                note("dry run on B, renumbering on: " + seconds(started) + " s");
                assertThat(dry.renumbering().nodes()).isEqualTo(CLASH);
                var refs = dry.renumbering().references();
                assertThat(refs.moved().values()).isGreaterThan(0);
                assertThat(refs.existing().values()).as("survivors' friend properties").isGreaterThan(0);
                assertThat(refs.newer().nodesWithReferences()).as("company nodes made after the backup").isGreaterThan(0);
                note("dry run found " + refs.moved().values() + " values in moved nodes, " + refs.existing().values()
                        + " in nodes already on the target, " + refs.newer().values() + " in newer nodes");
            }

            Timed on = merge("B, option on", sourceBolt, source, selection, dumpB, RenumberOptions.on());
            assertThat(on.result.renumberedNodes()).isEqualTo(CLASH);
            assertThat(on.result.rewrittenValues()).isGreaterThan(0);
            // in B the taken ids are not missing but renumbered, so the same nodes come back
            assertThat(on.result.restoredNodes()).isEqualTo(restoredA);
            note("merge B/A time: " + String.format("%.2f", (double) on.millis / off.millis) + "  (" + on.millis / 1000 + " s vs "
                    + off.millis / 1000 + " s)");
            check(on.output);
        }
    }

    // ---- one merge ----

    private record Timed(MergeResult result, long millis, Path output) {
    }

    private Timed merge(String name, Neo4jClient sourceBolt, ReaderProcess source, com.etolv.neo4jtool.engine.selection.SelectionConfig selection,
                        Path targetDump, RenumberOptions renumber) {
        Path output = temp.resolve(name.substring(0, 1) + "-merged-" + System.nanoTime() + ".dump");
        long started = System.nanoTime();
        MergeResult result = new MergeService().run(sourceBolt, source, selection, MergeTarget.dump(targetDump, "neo4j"), runner, work,
                temp.resolve("backups"), output,
                new MergeOptions(new BuildOptions(10_000, true, false, WORKERS, 0, "", ReaderProcess.factory(readerSettings)), false, renumber),
                phaseLog("merge " + name), log::add);
        long millis = (System.nanoTime() - started) / 1_000_000;
        note("merge into " + name + ": " + millis / 1000 + " s; " + result.restoredNodes() + " nodes restored, " + result.renumberedNodes()
                + " renumbered, " + result.rewrittenValues() + " properties rewritten in " + result.rewrittenNodes() + " nodes");
        result.phases().forEach(p -> note("    " + p.name() + ": " + p.millis() / 1000.0 + " s"));
        return new Timed(result, millis, output);
    }

    private DryRunProgress phaseLog(String what) {
        long[] last = {System.nanoTime()};
        String[] name = {"start"};
        return new DryRunProgress() {
            @Override
            public void phase(String phase) {
                note("  [" + what + "] " + (System.nanoTime() - last[0]) / 1_000_000 + " ms in '" + name[0] + "', now '" + phase + "'");
                last[0] = System.nanoTime();
                name[0] = phase;
            }
        };
    }

    // ---- the invariants ----

    private void check(Path merged) throws Exception {
        try (var instance = runner.startFromDump(merged, "neo4j", log::add);
             Neo4jClient client = new Neo4jClient(new ConnectionSettings(instance.boltUri(), null, null, "neo4j"));
             Session session = client.readSession()) {
            long nodes = session.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong();
            // all the source's nodes, plus the target's own new ones, plus the suppliers that sit on the taken ids
            assertThat(nodes).as("nodes").isEqualTo((long) NODES + NEWER + CLASH);

            long broken = session.run("MATCH (b:Booking)-[:FOR]->(c:Customer) WHERE b.custKey <> 'newer' AND b.custKey <> 'k' + substring(c.name, 1) "
                    + "RETURN count(b) AS c").single().get("c").asLong();
            assertThat(broken).as("bookings whose custKey does not name the customer they point at").isZero();

            long wrongInt = session.run("MATCH (b:Booking)-[:FOR]->(c:Customer) WHERE b.customer IS NOT NULL AND b.customer <> id(c) "
                    + "RETURN count(b) AS c").single().get("c").asLong();
            assertThat(wrongInt).as("bookings whose whole-number customer is not the id of their customer").isZero();
            long wrongText = session.run("MATCH (b:Booking)-[:FOR]->(c:Customer) WHERE b.customerText IS NOT NULL AND b.customerText <> toString(id(c)) "
                    + "RETURN count(b) AS c").single().get("c").asLong();
            assertThat(wrongText).as("text customers").isZero();
            long wrongList = session.run("MATCH (b:Booking)-[:FOR]->(c:Customer) WHERE b.customerList IS NOT NULL AND NOT id(c) IN b.customerList "
                    + "RETURN count(b) AS c").single().get("c").asLong();
            assertThat(wrongList).as("list customers").isZero();
            long floats = session.run("MATCH (b:Booking) WHERE b.customerFloat IS NOT NULL AND b.customerFloat <> toFloat(toInteger(substring(b.custKey, 1))) "
                    + "RETURN count(b) AS c").single().get("c").asLong();
            assertThat(floats).as("floats are not ids and keep their value").isZero();
            long friends = session.run("MATCH (s:Customer) WHERE s.friend IS NOT NULL MATCH (f) WHERE id(f) = s.friend AND NOT f.name = 'c' + substring(s.friendKey, 1) "
                    + "RETURN count(s) AS c").single().get("c").asLong();
            assertThat(friends).as("surviving customers whose friend is not the node they knew").isZero();
            long suppliers = session.run("MATCH (s:Supplier) RETURN count(s) AS c").single().get("c").asLong();
            assertThat(suppliers).as("the nodes that held the ids").isEqualTo(CLASH);
            long outside = session.run("MATCH (f:Filler) WHERE f.x IS NOT NULL AND f.x <> f.k % 1000 RETURN count(f) AS c").single().get("c").asLong();
            assertThat(outside).as("other companies' nodes are never changed").isZero();
            long newer = session.run("MATCH (b:Booking) WHERE b.custKey = 'newer' AND b.customer <> (toInteger(substring(b.ref, 5)) * 5) % " + (CUSTOMERS * 5)
                    + " RETURN count(b) AS c").single().get("c").asLong();
            assertThat(newer).as("company nodes made after the backup are left alone").isZero();
            note("invariants hold on " + nodes + " nodes");
        }
    }

    // ---- fixtures ----

    private static List<String> seed() {
        List<String> statements = new ArrayList<>();
        int slice = 100_000;
        for (int from = 0; from < NODES; from += slice) {
            int to = Math.min(NODES, from + slice) - 1;
            // one statement per kind keeps the ids in order: i % 5 == 0 customer, 1 booking, else filler
            statements.add("UNWIND range(" + from + ", " + to + ") AS i CREATE (n) SET n.org_id = CASE i % 5 WHEN 0 THEN 1 WHEN 1 THEN 1 ELSE 2 END "
                    + "FOREACH (_ IN CASE WHEN i % 5 = 0 THEN [1] ELSE [] END | SET n:Customer, n.name = 'c' + i) "
                    + "FOREACH (_ IN CASE WHEN i % 5 = 1 THEN [1] ELSE [] END | SET n:Booking, n.ref = 'B' + i) "
                    + "FOREACH (_ IN CASE WHEN i % 5 > 1 THEN [1] ELSE [] END | SET n:Filler, n.k = i, n.x = i % 1000)");
        }
        int customers = CUSTOMERS;
        for (int from = 0; from < NODES; from += slice) {
            int to = Math.min(NODES, from + slice) - 1;
            // bookings: the customer they point at, in four shapes by (i / 5) % 4
            String ref = "(((i / 5) * 7919 + (i / 5) / 7) % " + customers + ") * 5";
            statements.add("UNWIND range(" + from + ", " + to + ") AS i WITH i WHERE i % 5 = 1 WITH i, " + ref + " AS ref MATCH (b) WHERE id(b) = i "
                    + "SET b.custKey = 'k' + ref "
                    + "FOREACH (_ IN CASE WHEN (i / 5) % 4 = 0 THEN [1] ELSE [] END | SET b.customer = ref) "
                    + "FOREACH (_ IN CASE WHEN (i / 5) % 4 = 1 THEN [1] ELSE [] END | SET b.customerText = toString(ref)) "
                    + "FOREACH (_ IN CASE WHEN (i / 5) % 4 = 2 THEN [1] ELSE [] END | SET b.customerList = [ref]) "
                    + "FOREACH (_ IN CASE WHEN (i / 5) % 4 = 3 THEN [1] ELSE [] END | SET b.customerFloat = toFloat(ref)) "
                    + "WITH b, ref MATCH (c) WHERE id(c) = ref CREATE (b)-[:FOR]->(c)");
        }
        // a tenth of the customers survive on the target; each knows another customer by id
        for (int from = 0; from < NODES; from += slice) {
            int to = Math.min(NODES, from + slice) - 1;
            // half of them know a customer whose id is taken in B, the others another one
            String friend = "CASE (i / 50) % 2 WHEN 0 THEN (((i / 50) * 200 + 7) % " + customers + ") * 5 ELSE (((i / 5 + 13) * 31) % " + customers + ") * 5 END";
            statements.add("UNWIND range(" + from + ", " + to + ") AS i WITH i WHERE i % 50 = 15 WITH i, " + friend + " AS f MATCH (s) WHERE id(s) = i "
                    + "SET s.friend = f, s.friendKey = 'k' + f WITH s, f MATCH (x) WHERE id(x) = f CREATE (s)-[:KNOWS]->(x)");
        }
        statements.add("CREATE CONSTRAINT customer_name FOR (c:Customer) REQUIRE c.name IS UNIQUE");
        return statements;
    }

    /** The target: the source, the company's new bookings, then the company is lost except the customers with i % 50 == 15. */
    private static List<String> target(List<String> seed, List<String> newer, boolean taken) {
        List<String> statements = new ArrayList<>(seed);
        statements.addAll(newer);
        int slice = 100_000;
        for (int from = 0; from < NODES; from += slice) {
            int to = Math.min(NODES, from + slice) - 1;
            // the ids that the company lost: all of its nodes except the survivors and (in B) the taken ids
            statements.add("MATCH (n) WHERE id(n) >= " + from + " AND id(n) <= " + to + " AND n.org_id = 1 AND id(n) % 50 <> 15 AND NOT "
                    + (taken ? "(n:Customer AND (id(n) / 5) % 200 = 7)" : "false") + " DETACH DELETE n");
        }
        if (taken) {
            statements.add("MATCH (n:Customer) WHERE (id(n) / 5) % 200 = 7 AND id(n) % 50 <> 15 REMOVE n:Customer SET n:Supplier, n.org_id = 2, n.name = 'supplier ' + id(n)");
        }
        return statements;
    }

    private void note(String line) {
        report.add(line);
        System.out.println("SCALE " + line);
    }

    private static long seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }
}
