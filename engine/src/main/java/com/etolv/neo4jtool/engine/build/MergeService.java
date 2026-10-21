package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.Cypher;
import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.instance.InstanceRunner;
import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import com.etolv.neo4jtool.engine.instance.Workspace;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * Puts the selected nodes of the source that are missing on the target back, with their ids, by building a new database
 * that holds everything the target has plus those nodes.
 *
 * <p>The target is never written to while it is read: it is dumped first (that dump is the mandatory backup and is
 * kept), and everything is read from a temporary server that serves the backup. The new database is built, compared with
 * what was exported and checked. Only then, and only if asked, is it swapped into the stopped server's data directory,
 * with the old database kept next to it so that {@link #rollback} can bring it back.
 *
 * <p>Stops, changing nothing, when a selected id is used by a different node on the target, when nothing is missing, or
 * when the data directory changed since the backup (a server was started on it).
 */
public final class MergeService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @param source          where the nodes come from (read only)
     * @param backupDirectory where the backup dump of a stopped data directory is kept
     * @param outputFile      where the merged dump is written when nothing is swapped in; it must not exist yet
     */
    public MergeResult run(Neo4jClient source, SelectionConfig selection, MergeTarget target, InstanceRunner runner, Path workDir,
                           Path backupDirectory, Path outputFile, MergeOptions options, DryRunProgress progress,
                           Consumer<String> log) {
        boolean swap = target.dataDirectory() && options.swap();
        if (!swap && (outputFile == null || Files.exists(outputFile))) {
            throw new BuildException((outputFile == null ? "Give a file" : outputFile + " already exists")
                    + " for the merged dump. Nothing was changed.");
        }
        Instant started = Instant.now();
        String stamp = LocalDateTime.now().format(STAMP);
        Phases phases = new Phases(progress);
        String database = target.database();
        Workspace workspace = Workspace.create(workDir, "merge");
        try {
            phases.start("Backing up the target");
            String signature = target.dataDirectory() ? StoreSignature.of(target.path(), database) : null;
            Path backup = backup(runner, target, backupDirectory, workspace, stamp, log);
            log.accept("Backup: " + backup);

            Built built;
            try (RunningInstance targetServer = runner.startFromDump(backup, database, log);
                 Neo4jClient targetClient = new Neo4jClient(new ConnectionSettings(targetServer.boltUri(), null, null, database))) {
                targetClient.verify();
                built = build(source, targetClient, selection, runner, workspace, database, options, outputFile, swap, phases,
                        progress, log);
            }

            String keepName = null;
            if (swap) {
                phases.start("Swapping the new database into the data directory");
                if (!StoreSignature.of(target.path(), database).equals(signature)) {
                    throw new BuildException("The files of '" + database + "' in " + target.path() + " changed after the backup "
                            + "was taken. Is its server running? Nothing was swapped; the new database was discarded. "
                            + "Stop the server and run again.");
                }
                keepName = "before-" + stamp;
                runner.swapDatabase(target.path(), workspace.directory("data"), database, keepName, log);
            }
            return result(started, database, target, built, backup, outputFile, swap, keepName, options, phases);
        } catch (IOException e) {
            throw new BuildException("Could not write a file: " + e.getMessage(), e);
        } finally {
            runner.deleteWorkspace(workspace);
        }
    }

    /** Puts the old database of a swapped merge back. The new one is set aside, not deleted. The server must be stopped. */
    public MergeResult rollback(MergeResult result, InstanceRunner runner, Consumer<String> log) {
        if (!result.swapped() || result.rolledBackAt() != null) {
            throw new BuildException("This merge has nothing to roll back");
        }
        runner.restoreDatabase(Path.of(result.dataDirectory()), result.database(), result.keepName(), log);
        return result.withRolledBackAt(Instant.now().toString());
    }

    // ---- steps ----

    /** What was built, for the result. */
    private record Built(IdSet target, IdSet restored, long same, CsvExporter.Export targetExport, CsvExporter.Export restoredExport,
                         int schemaStatements, Path dump) {
    }

    private Built build(Neo4jClient source, Neo4jClient target, SelectionConfig selection, InstanceRunner runner,
                        Workspace workspace, String database, MergeOptions options, Path outputFile, boolean swap,
                        Phases phases, DryRunProgress progress, Consumer<String> log) throws IOException {
        BuildOptions build = options.build();
        String org = selection.orgProperty();

        phases.start("Comparing the selection with the target");
        IdSet targetIds = Ids.all(target);
        IdSet selected = Ids.selected(source, selection);
        IdSet missing = selected.minus(targetIds);
        IdSet present = selected.intersect(targetIds);
        IdSet clash = differing(source, target, present, org, build.batchSize());
        if (clash.size() > 0) {
            throw new BuildException(clash.size() + " selected ids are used by a different node on the target (the first is "
                    + clash.toArray()[0] + "). Restoring would lose one of the two nodes. Nothing was changed; see the dry run.");
        }
        if (missing.size() == 0) {
            throw new BuildException("Nothing to restore: " + (selected.size() == 0 ? "the selection matches no node in the source"
                    : "all " + selected.size() + " selected nodes are already on the target") + ". Nothing was changed.");
        }
        IdSet confirmed = sameNeighbours(source, target, targetIds, missing, org, build.batchSize());
        IdSet allowedEnds = IdSet.union(missing, confirmed);
        log.accept(targetIds.size() + " nodes on the target, " + missing.size() + " to restore, " + present.size()
                + " already there; highest id " + Math.max(targetIds.max(), missing.max()));

        Path csv = workspace.directory("csv");
        phases.start("Exporting the target to CSV");
        CsvExporter.Export targetExport = new CsvExporter(CsvExporter.bolt(target), targetIds,
                List.of(CsvExporter.Rule.out(targetIds)), "target-", csv, build.batchSize(), build.workers(), progress).export();
        phases.start("Exporting the nodes to restore to CSV");
        CsvExporter.Export restoredExport = new CsvExporter(CsvExporter.bolt(source), missing,
                List.of(CsvExporter.Rule.out(allowedEnds), CsvExporter.Rule.in(confirmed)), "restored-", csv, build.batchSize(),
                build.workers(), progress).export();
        log.accept(restoredExport.nodes() + " nodes and " + restoredExport.relationships() + " relationships restored; "
                + restoredExport.relationshipsToOutside() + " relationships left out because their other end is not on the target");

        List<String> schema = build.copySchema() ? SchemaCopy.statements(target) : List.of();
        List<String> nodeFiles = new ArrayList<>(targetExport.nodeFiles());
        nodeFiles.addAll(restoredExport.nodeFiles());
        List<String> relationshipFiles = new ArrayList<>(targetExport.relationshipFiles());
        relationshipFiles.addAll(restoredExport.relationshipFiles());
        NewDatabase.build(runner, csv, workspace.directory("data"), database, nodeFiles, relationshipFiles,
                List.of(targetExport.fixups(), restoredExport.fixups()),
                schema, Verifier.Expected.of(IdSet.union(targetIds, missing), targetExport, restoredExport),
                build, phases, log);

        Path dump = null;
        if (!swap) {
            phases.start("Writing the merged dump");
            Path produced = runner.dumpStoppedDatabase(workspace.directory("data"), database, workspace.directory("dump"), log);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            dump = Files.move(produced, outputFile);
        }
        return new Built(targetIds, missing, present.size(), targetExport, restoredExport, schema.size(), dump);
    }

    private Path backup(InstanceRunner runner, MergeTarget target, Path backupDirectory, Workspace workspace, String stamp,
                        Consumer<String> log) throws IOException {
        if (!target.dataDirectory()) {
            if (!Files.isRegularFile(target.path())) {
                throw new BuildException("The dump " + target.path() + " does not exist");
            }
            log.accept("The target is a dump file; it is its own backup and is not changed");
            return target.path();
        }
        Path dump = runner.dumpStoppedDatabase(target.path(), target.database(), workspace.directory("backup"), log);
        Files.createDirectories(backupDirectory);
        Path backup = backupDirectory.resolve(target.database() + "-before-merge-" + stamp + ".dump");
        if (Files.exists(backup)) {
            throw new BuildException(backup + " already exists; wait a second and run again");
        }
        return Files.move(dump, backup);
    }

    private MergeResult result(Instant started, String database, MergeTarget target, Built built, Path backup, Path outputFile,
                               boolean swap, String keepName, MergeOptions options, Phases phases) throws IOException {
        return new MergeResult(started.toString(), Instant.now().toString(), database, built.target().size(),
                built.restored().size(), built.same(), built.targetExport().relationships(), built.restoredExport().relationships(),
                built.restoredExport().relationshipsToOutside(),
                built.targetExport().fixups().size() + built.restoredExport().fixups().size(), built.schemaStatements(),
                options.build().checkConsistency(), backup.toAbsolutePath().toString(), Files.size(backup),
                built.dump() == null ? null : built.dump().toAbsolutePath().toString(),
                built.dump() == null ? 0 : Files.size(built.dump()), swap, swap ? target.path().toAbsolutePath().toString() : null,
                keepName, null, phases.all());
    }

    // ---- comparing source and target ----

    /** The ids (of {@code ids}) whose node on the source is not the same node as on the target: other labels or other org. */
    private static IdSet differing(Neo4jClient source, Neo4jClient target, IdSet ids, String org, int batch) {
        LongArrayList different = new LongArrayList();
        for (long from = 0; from < ids.size(); from += batch) {
            long[] slice = ids.slice(from, Math.min(ids.size(), from + batch));
            Long2ObjectOpenHashMap<String> inSource = identities(source, slice, org);
            Long2ObjectOpenHashMap<String> inTarget = identities(target, slice, org);
            for (long id : slice) {
                if (!inSource.getOrDefault(id, "").equals(inTarget.getOrDefault(id, "?"))) {
                    different.add(id);
                }
            }
        }
        return IdSet.of(different);
    }

    /**
     * The nodes next to the nodes to restore that the target has as the same node. A relationship to any other node is
     * left out: the other node is not on the target, or the id there belongs to a different node.
     */
    private static IdSet sameNeighbours(Neo4jClient source, Neo4jClient target, IdSet targetIds, IdSet missing, String org, int batch) {
        LongOpenHashSet neighbours = new LongOpenHashSet();
        for (long from = 0; from < missing.size(); from += batch) {
            long[] slice = missing.slice(from, Math.min(missing.size(), from + batch));
            try (Session session = source.readSession()) {
                Result result = session.run("UNWIND $ids AS i MATCH (a) WHERE id(a) = i MATCH (a)--(b) RETURN DISTINCT id(b) AS id",
                        Map.of("ids", Values.value(slice)));
                while (result.hasNext()) {
                    neighbours.add(result.next().get("id").asLong());
                }
            }
        }
        IdSet candidates = IdSet.of(neighbours.toLongArray()).minus(missing).intersect(targetIds);
        IdSet different = differing(source, target, candidates, org, batch);
        return candidates.minus(different);
    }

    /** Labels and org of each node, as one text, so that two nodes are the same when the texts are equal. */
    private static Long2ObjectOpenHashMap<String> identities(Neo4jClient client, long[] ids, String org) {
        Long2ObjectOpenHashMap<String> found = new Long2ObjectOpenHashMap<>();
        try (Session session = client.readSession()) {
            Result result = session.run("UNWIND $ids AS i MATCH (n) WHERE id(n) = i RETURN id(n) AS id, labels(n) AS labels, n."
                    + Cypher.ident(org) + " AS org", Map.of("ids", Values.value(ids)));
            while (result.hasNext()) {
                Record r = result.next();
                List<String> labels = new ArrayList<>(r.get("labels").asList(Value::asString));
                Collections.sort(labels);
                Value value = r.get("org");
                found.put(r.get("id").asLong(), String.join(",", labels) + "|"
                        + (value.isNull() ? "-" : value.type().name() + ":" + value.asObject()));
            }
        }
        return found;
    }
}
