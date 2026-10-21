package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.schema.SchemaReader;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import com.etolv.neo4jtool.web.MigrationPlan.Side;
import com.etolv.neo4jtool.web.MigrationPlan.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

/** Builds the plan of a migration from the two databases. Reads only. */
@Component
public class Planner {

    // The dry run compared about 97,000 nodes per second on a 2-CPU test machine that also ran both servers.
    private static final long NODES_PER_SECOND = 50_000;

    public MigrationPlan plan(Endpoint source, Neo4jClient from, Endpoint target, Neo4jClient to,
                              SelectionConfig selection) {
        Side fromSide = side(source, from, selection.orgProperty());
        Side toSide = target == null ? null : side(target, to, selection.orgProperty());
        long selected = count(from, SelectionQueryBuilder.nodeCount(selection));

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
        if (target != null && target.kind() == Endpoint.Kind.LIVE) {
            warnings.add("Merging into a running server is not possible in Neo4j Community: the target has to be stopped.");
        }

        return new MigrationPlan(Instant.now().toString(), target == null ? "SPLIT" : "MERGE", fromSide, toSide, selection,
                selection.selectsEverything(), selected, Math.max(1, selected / NODES_PER_SECOND),
                steps(source, target), warnings);
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
            steps.add(new Step(2, "Dry run against '" + target.name() + "'",
                    "Reports which ids are missing, already the same node, or used by a different node.", true));
            steps.add(new Step(3, "Back up '" + target.name() + "' with a dump (required)", "Not available in this version.", false));
            steps.add(new Step(4, "Build a database that holds everything in '" + target.name() + "' plus the selected nodes, ids kept",
                    "Not available in this version.", false));
            steps.add(new Step(5, target.kind() == Endpoint.Kind.DUMP
                    ? "Write the result as a new dump file; the dump you chose is not changed"
                    : "Check it and swap it in; the old database is kept next to it", "Not available in this version.", false));
        }
        return steps;
    }

    private static Side side(Endpoint endpoint, Neo4jClient client, String orgProperty) {
        SchemaSnapshot schema = new SchemaReader(client, orgProperty, false).read();
        return new Side(endpoint.name(), endpoint.kind().label(), schema.serverVersion(), schema.database(),
                schema.totalNodes(), schema.totalRelationships(), schema.indexes().size(), schema.constraints().size());
    }

    private static long count(Neo4jClient client, CypherQuery query) {
        try (Session session = client.readSession()) {
            return session.run(query.text(), query.params()).single().get("c").asLong();
        }
    }

    private static String majorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }
}
