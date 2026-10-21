package com.etolv.neo4jtool.engine.split;

import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.instance.ImportFiles;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.Workspace;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

/**
 * Copies the selected nodes, and the relationships between them, into a new database with the node ids kept, and writes
 * that database as a dump file. Nothing is written to the source: it is only read.
 *
 * <p>Steps: read the selected ids; export them as CSV; build a database from the CSV with
 * {@code neo4j-admin database import full --id-type=actual}; start a throw-away server on it to put back what the
 * import cannot carry and to create indexes and constraints; read the new database back and compare it with what was
 * exported; stop the server; check the store; dump it. If anything fails the new database is discarded.
 */
public final class SplitService {

    /**
     * @param source     the database the nodes are copied from (read only)
     * @param runner     builds and serves the new database (Docker or a local Neo4j)
     * @param workDir    scratch space: it needs room for the CSV files, the new database and its dump
     * @param outputFile where the dump is written; it must not exist yet
     */
    public SplitResult run(Neo4jClient source, SelectionConfig selection, InstanceRunner runner, Path workDir, Path outputFile,
                           SplitOptions options, DryRunProgress progress, Consumer<String> log) {
        if (Files.exists(outputFile)) {
            throw new SplitException(outputFile + " already exists. Nothing was changed; choose another name or remove it.");
        }
        Instant started = Instant.now();
        List<SplitResult.Phase> phases = new ArrayList<>();
        Timer timer = new Timer(phases, progress, log);
        String database = source.database();

        timer.phase("Reading the selected ids");
        IdSet ids = loadIds(source, selection);
        if (ids.size() == 0) {
            throw new SplitException("The selection matches no node in the source. Nothing was copied.");
        }
        log.accept(ids.size() + " nodes selected, highest id " + ids.max());

        Workspace workspace = Workspace.create(workDir, "split");
        try {
            Path csv = workspace.directory("csv");
            Path data = workspace.directory("data");
            Path dumps = workspace.directory("dump");

            timer.phase("Exporting the selection to CSV");
            CsvExporter.Export export = new CsvExporter(source, ids, csv, options.batchSize(), progress).export();
            log.accept(export.nodes() + " nodes, " + export.relationships() + " relationships exported; "
                    + export.fixups().size() + " properties will be written afterwards; "
                    + export.relationshipsToOutside() + " relationships to nodes outside the selection are left out");

            timer.phase("Building the new database with neo4j-admin import (ids kept)");
            runner.importDatabase(data, database,
                    new ImportFiles(csv, export.nodeFiles(), export.relationshipFiles(), ValueCodec.ARRAY_DELIMITER), log);

            List<String> schema = options.copySchema() ? SchemaCopy.statements(source) : List.of();
            timer.phase("Finishing the new database on a temporary server");
            try (RunningInstance server = runner.serveDataDirectory(data, database, log)) {
                try (DatabaseWriter writer = new DatabaseWriter(server.boltUri(), database)) {
                    if (export.fixups().size() > 0) {
                        log.accept("Writing " + export.fixups().size() + " properties the import cannot carry");
                        writer.applyFixups(source, export.fixups());
                    }
                    if (!schema.isEmpty()) {
                        log.accept("Creating " + schema.size() + " indexes and constraints");
                        writer.createSchema(schema);
                    }
                }
                timer.phase("Comparing the new database with the source data");
                try (Neo4jClient built = new Neo4jClient(new ConnectionSettings(server.boltUri(), null, null, database))) {
                    built.verify();
                    Verifier.verify(built, ids, export);
                }
                timer.phase("Stopping the temporary server");
            }

            if (options.checkConsistency()) {
                timer.phase("Checking the store (neo4j-admin database check)");
                UnusedRecords unused = new UnusedRecords(log);
                runner.checkDatabase(data, database, unused);
                unused.summarize();
            }

            timer.phase("Writing the dump");
            Path dump = runner.dumpStoppedDatabase(data, database, dumps, log);
            Path parent = outputFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Files.move(dump, outputFile);
            timer.finish();

            return new SplitResult(started.toString(), Instant.now().toString(), database, export.nodes(), export.relationships(),
                    export.relationshipsToOutside(), export.fixups().size(), schema.size(), options.checkConsistency(),
                    outputFile.toAbsolutePath().toString(), Files.size(outputFile),
                    Long.toHexString(export.nodeFingerprint()), Long.toHexString(export.relationshipFingerprint()), phases);
        } catch (IOException e) {
            throw new SplitException("Could not write " + outputFile + ": " + e.getMessage(), e);
        } finally {
            runner.deleteWorkspace(workspace);
        }
    }

    private static IdSet loadIds(Neo4jClient source, SelectionConfig selection) {
        CypherQuery query = SelectionQueryBuilder.idStream(selection);
        LongArrayList ids = new LongArrayList();
        try (Session session = source.readSession()) {
            Result result = session.run(query.text(), query.params());
            while (result.hasNext()) {
                Record r = result.next();
                ids.add(r.get("id").asLong());
            }
        }
        return IdSet.of(ids);
    }

    /**
     * The new store has a slot for every id up to the highest one, and the ids that are not selected stay unused. The
     * check lists each of them as a warning, which can be thousands of lines; they are counted instead of logged.
     */
    private static final class UnusedRecords implements Consumer<String> {
        private final Consumer<String> log;
        private long count;

        UnusedRecords(Consumer<String> log) {
            this.log = log;
        }

        @Override
        public void accept(String line) {
            if (line.contains("is not in use but the id is not up for reuse")) {
                count++;
            } else if (!(line.stripLeading().startsWith("Node[") && line.contains("used=false"))) {
                log.accept(line);
            }
        }

        void summarize() {
            if (count > 0) {
                log.accept(count + " unused node slots were reported by the check. That is expected: ids that are not selected "
                        + "leave gaps in the new database.");
            }
        }
    }

    /** Names the step in progress and records how long each step took. */
    private static final class Timer {
        private final List<SplitResult.Phase> phases;
        private final DryRunProgress progress;
        private final Consumer<String> log;
        private String current;
        private long since;

        Timer(List<SplitResult.Phase> phases, DryRunProgress progress, Consumer<String> log) {
            this.phases = phases;
            this.progress = progress;
            this.log = log;
        }

        void phase(String name) {
            finish();
            current = name;
            since = System.nanoTime();
            progress.phase(name);
        }

        void finish() {
            if (current != null) {
                phases.add(new SplitResult.Phase(current, (System.nanoTime() - since) / 1_000_000));
                current = null;
            }
        }
    }
}
