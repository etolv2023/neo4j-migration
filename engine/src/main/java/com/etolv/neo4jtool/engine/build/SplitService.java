package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.Workspace;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

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
                           BuildOptions options, DryRunProgress progress, Consumer<String> log) {
        return run(source, null, selection, runner, workDir, outputFile, options, progress, log);
    }

    /**
     * @param fast reads the source with the kernel on all threads when it is not null; {@code source} is still used for the
     *             few Cypher questions (the schema) and may be served by the same process
     */
    public SplitResult run(Neo4jClient source, FastReader fast, SelectionConfig selection, InstanceRunner runner, Path workDir,
                           Path outputFile, BuildOptions options, DryRunProgress progress, Consumer<String> log) {
        if (Files.exists(outputFile)) {
            throw new BuildException(outputFile + " already exists. Nothing was changed; choose another name or remove it.");
        }
        Instant started = Instant.now();
        Phases phases = new Phases(progress);
        String database = source.database();

        phases.start("Reading the selected ids");
        IdSet ids = fast != null ? fast.select(selection, options.workers(), progress) : Ids.selected(source, selection);
        if (ids.size() == 0) {
            throw new BuildException("The selection matches no node in the source. Nothing was copied.");
        }
        log.accept(ids.size() + " nodes selected, highest id " + ids.max());

        Workspace workspace = Workspace.create(workDir, "split");
        try {
            Path csv = workspace.directory("csv");
            Path data = workspace.directory("data");
            Path dumps = workspace.directory("dump");

            phases.start("Exporting the selection to CSV");
            List<CsvExporter.Rule> rules = List.of(CsvExporter.Rule.out(ids));
            CsvExporter.Export export = fast != null
                    ? fast.export(ids, rules, "", csv, options.batchSize(), options.workers(), progress)
                    : new CsvExporter(CsvExporter.bolt(source), ids, rules, "", csv, options.batchSize(), options.workers(),
                            progress).export();
            log.accept(export.nodes() + " nodes, " + export.relationships() + " relationships exported; "
                    + export.fixups().size() + " properties will be written afterwards; "
                    + export.relationshipsToOutside() + " relationships to nodes outside the selection are left out");

            List<String> schema = options.copySchema() ? SchemaCopy.statements(source) : List.of();
            NewDatabase.build(runner, csv, data, database, export.nodeFiles(), export.relationshipFiles(),
                    List.of(export.fixups()), schema,
                    Verifier.Expected.of(ids, export), options, phases, log);

            phases.start("Writing the dump");
            Path dump = runner.dumpStoppedDatabase(data, database, dumps, log);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            Files.move(dump, outputFile);

            return new SplitResult(started.toString(), Instant.now().toString(), database, export.nodes(), export.relationships(),
                    export.relationshipsToOutside(), export.fixups().size(), schema.size(), options.checkConsistency(),
                    outputFile.toAbsolutePath().toString(), Files.size(outputFile),
                    Long.toHexString(export.nodeFingerprint()), Long.toHexString(export.relationshipFingerprint()), phases.all());
        } catch (IOException e) {
            throw new BuildException("Could not write " + outputFile + ": " + e.getMessage(), e);
        } finally {
            runner.deleteWorkspace(workspace);
        }
    }
}
