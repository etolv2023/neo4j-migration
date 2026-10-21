package com.etolv.neo4jtool.cli;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.schema.SchemaReader;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.LabelInfo;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "schema", mixinStandardHelpOptions = true,
        description = "Prints labels, relationship types, indexes, constraints and per-organisation counts.")
final class SchemaCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection = new ConnectionOptions();

    @Option(names = "--org-property", description = "Property that identifies the organisation (default: ${DEFAULT-VALUE})",
            defaultValue = "org_id")
    String orgProperty;

    @Option(names = "--no-org-breakdown", description = "Skip per-organisation counts (they scan every node)")
    boolean noOrgBreakdown;

    @Option(names = "--json", description = "Print the snapshot as JSON")
    boolean json;

    @Override
    public Integer call() {
        try (Neo4jClient client = connection.open()) {
            SchemaSnapshot snapshot = new SchemaReader(client, orgProperty, !noOrgBreakdown).read();
            if (json) {
                System.out.println(Json.writePretty(snapshot));
            } else {
                print(snapshot);
            }
            return 0;
        }
    }

    private static void print(SchemaSnapshot s) {
        System.out.printf("Neo4j %s, database '%s'%n", s.serverVersion(), s.database());
        System.out.printf("%,d nodes, %,d relationships%n%n", s.totalNodes(), s.totalRelationships());
        System.out.println("Labels");
        for (LabelInfo label : s.labels()) {
            System.out.printf("  %-30s %,12d%n", label.name(), label.count());
        }
        System.out.println("Relationship types");
        s.relationshipTypes().forEach(t -> System.out.printf("  %-30s %,12d%n", t.name(), t.count()));
        System.out.println("Indexes: " + s.indexes().size() + ", constraints: " + s.constraints().size());
        if (s.orgBreakdownIncluded()) {
            System.out.println("Nodes per " + s.orgProperty() + " (all labels)");
            java.util.Map<Object, Long> perOrg = new java.util.TreeMap<>(java.util.Comparator.comparing(String::valueOf));
            s.labels().forEach(l -> l.byOrg().forEach(o -> perOrg.merge(o.org(), o.count(), Long::sum)));
            perOrg.forEach((org, count) -> System.out.printf("  %-30s %,12d%n", org, count));
            System.out.println("  (a node with several labels is counted once per label)");
        }
    }
}
