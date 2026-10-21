package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.schema.SchemaReader;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import com.etolv.neo4jtool.web.MigrationPlan.LabelCount;
import com.etolv.neo4jtool.web.MigrationPlan.Side;
import com.etolv.neo4jtool.web.MigrationPlan.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

/** Builds the plan of a migration from the two databases. Reads only. */
@Component
public class Planner {

    // The dry run compared about 97,000 nodes per second on a 2-CPU test machine that also ran both servers.
    private static final long NODES_PER_SECOND = 50_000;
    // The node store of Neo4j 5 keeps 15 bytes for every id up to the highest one in use.
    private static final long NODE_RECORD_BYTES = 15;
    private static final long LARGE_STORE_BYTES = 100L * 1024 * 1024;

    public MigrationPlan plan(Endpoint source, Neo4jClient from, Endpoint target, Neo4jClient to,
                              SelectionConfig selection) {
        Side fromSide = side(source, from, selection.orgProperty());
        Side toSide = target == null ? null : side(target, to, selection.orgProperty());
        long selected;
        long highestId;
        CypherQuery stats = SelectionQueryBuilder.nodeStats(selection);
        try (Session session = from.readSession()) {
            var row = session.run(stats.text(), stats.params()).single();
            selected = row.get("c").asLong();
            highestId = row.get("maxId").isNull() ? -1 : row.get("maxId").asLong();
        }
        List<LabelCount> neighbourLabels = neighbourLabels(from, selection);
        long neighbours = neighbourLabels.isEmpty() ? 0 : selected - selectedWithout(from, selection);

        List<String> warnings = new ArrayList<>();
        if (selection.selectsEverything()) {
            warnings.add("The selection has no filter, so it is every node of the source.");
        }
        if (selected == 0) {
            warnings.add("Nothing in the source matches the selection. Check the organisation property ('"
                    + selection.orgProperty() + "'), the labels and the ids.");
        }
        if (target != null && source.sameDatabaseAs(target)) {
            warnings.add("The source and the target are the same database.");
        }
        if (target != null && !majorMinor(fromSide.serverVersion()).equals(majorMinor(toSide.serverVersion()))) {
            warnings.add("The servers have different versions (" + fromSide.serverVersion() + " and "
                    + toSide.serverVersion() + "). Ids are kept either way, but store formats may differ.");
        }
        if (source.kind() != Endpoint.Kind.LIVE || (target != null && target.kind() != Endpoint.Kind.LIVE)) {
            warnings.add("A dump or a stopped data directory is loaded into a temporary server first. For a big database "
                    + "that takes a while and needs disk space in the work directory.");
        }
        long storeBytes = (highestId + 1) * NODE_RECORD_BYTES;
        if (target == null && highestId + 1 > 10 * Math.max(1, selected) && storeBytes > LARGE_STORE_BYTES) {
            warnings.add("The new database keeps a slot for every node id up to the highest selected one (" + highestId
                    + "), so it will take about " + megabytes(storeBytes) + " for the nodes alone, although only " + selected
                    + " nodes are selected. This is how Neo4j stores ids with ids kept; make sure the work and output "
                    + "directories have room.");
        }
        if (source.kind() == Endpoint.Kind.LIVE && !selection.orgIds().isEmpty() && selected > 0
                && !hasIndexOn(from, selection.orgProperty())) {
            warnings.add("No index starts with '" + selection.orgProperty() + "' in the source. A running server then looks at "
                    + "every node to find the companies, which takes hours on billions of nodes. Create one per label "
                    + "(CREATE INDEX FOR (n:Label) ON (n." + selection.orgProperty() + ")), or read a dump or a stopped data "
                    + "directory instead: the fast reader scans the store directly and does not need an index.");
        }
        if (target != null && target.kind() == Endpoint.Kind.LIVE) {
            warnings.add("Merging into a running server is not possible in Neo4j Community: the target has to be stopped.");
        }

        return new MigrationPlan(Instant.now().toString(), target == null ? "SPLIT" : "MERGE", fromSide, toSide, selection,
                selection.selectsEverything(), selected, neighbours, neighbourLabels, highestId, storeBytes, Math.max(1, selected / NODES_PER_SECOND),
                steps(source, target), warnings);
    }

    /** True when an online index has the property as its first (or only) property; unknown counts as true. */
    private static boolean hasIndexOn(Neo4jClient client, String property) {
        try (Session session = client.readSession()) {
            return session.run("SHOW INDEXES YIELD state, properties, entityType WHERE state = 'ONLINE' AND entityType = 'NODE' "
                    + "RETURN properties").list().stream()
                    .anyMatch(r -> !r.get("properties").asList(v -> v.asString()).isEmpty()
                            && r.get("properties").asList(v -> v.asString()).get(0).equals(property));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The nodes that come along only because they are connected to a selected node, by label: what the selection holds
     * with neighbours minus what it holds without them.
     */
    private static List<LabelCount> neighbourLabels(Neo4jClient from, SelectionConfig selection) {
        if (!selection.includeNeighbours() || selection.selectsEverything()) {
            return List.of();
        }
        Map<String, Long> counts = new TreeMap<>();
        labelCounts(from, selection).forEach((label, count) -> counts.put(label, count));
        labelCounts(from, selection.withNeighbours(false)).forEach((label, count) -> counts.merge(label, -count, Long::sum));
        List<LabelCount> result = new ArrayList<>();
        counts.forEach((label, count) -> {
            if (count > 0) {
                result.add(new LabelCount(label, count));
            }
        });
        result.sort(Comparator.comparingLong(LabelCount::count).reversed().thenComparing(LabelCount::label));
        return result;
    }

    private static long selectedWithout(Neo4jClient from, SelectionConfig selection) {
        CypherQuery query = SelectionQueryBuilder.nodeCount(selection.withNeighbours(false));
        try (Session session = from.readSession()) {
            return session.run(query.text(), query.params()).single().get("c").asLong();
        }
    }

    private static Map<String, Long> labelCounts(Neo4jClient client, SelectionConfig selection) {
        CypherQuery query = SelectionQueryBuilder.labelCounts(selection);
        Map<String, Long> counts = new TreeMap<>();
        try (Session session = client.readSession()) {
            session.run(query.text(), query.params()).forEachRemaining(r -> counts.put(r.get("label").asString(), r.get("c").asLong()));
        }
        return counts;
    }

    private static List<Step> steps(Endpoint source, Endpoint target) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(1, "Read the selection from '" + source.name() + "'",
                "Collects the ids of the selected nodes. Reads only.", true));
        if (target == null) {
            steps.add(new Step(2, "Dry run", "Looks at the selected nodes and at the relationships that leave the selection.", true));
            steps.add(new Step(3, "Export the selected nodes and relationships with their ids",
                    "Writes CSV files with the exact value types; the source is only read.", true));
            steps.add(new Step(4, "Build a new database from the export with ids kept, and check it",
                    "neo4j-admin import, then indexes and constraints, then a comparison with the source and a store check.", true));
            steps.add(new Step(5, "Write the new database as a dump file", "Into the output directory from the settings. Nothing existing is overwritten.", true));
        } else {
            boolean runnable = target.kind() != Endpoint.Kind.LIVE;
            String notLive = "Needs the target to be a stopped data directory or a dump, not a running server.";
            steps.add(new Step(2, "Dry run against '" + target.name() + "'",
                    "Reports which ids are missing, already the same node, or used by a different node.", true));
            steps.add(new Step(3, "Back up '" + target.name() + "' with a dump (required)",
                    runnable ? "A dump of the stopped target is written to the backup directory first and kept. A dump target is its own backup."
                            : notLive, runnable));
            steps.add(new Step(4, "Build a database that holds everything in '" + target.name() + "' plus the selected nodes, ids kept",
                    runnable ? "Exports the target and the missing nodes, imports them with ids kept, compares and checks the result."
                            : notLive, runnable));
            steps.add(new Step(5, target.kind() == Endpoint.Kind.DUMP
                    ? "Write the result as a new dump file; the dump you chose is not changed"
                    : "Swap it in (or write a dump, by setting); the old database is kept next to it and can be rolled back",
                    runnable ? "Into the data directory, only if its files have not changed since the backup." : notLive, runnable));
        }
        return steps;
    }

    private static Side side(Endpoint endpoint, Neo4jClient client, String orgProperty) {
        SchemaSnapshot schema = new SchemaReader(client, orgProperty, false).read();
        return new Side(endpoint.name(), endpoint.kind().label(), schema.serverVersion(), schema.database(),
                schema.totalNodes(), schema.totalRelationships(), schema.indexes().size(), schema.constraints().size());
    }

    private static String megabytes(long bytes) {
        return String.format("%,d MB", bytes / 1024 / 1024);
    }

    private static String majorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }
}
