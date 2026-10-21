package com.etolv.neo4jtool.engine.build;

import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Value;

/**
 * Reads the finished database back and compares it with what the export wrote: the same node ids (no more, no fewer),
 * and, over everything about every node and relationship, the same fingerprint. A value that changed type or
 * content on the way would show up here.
 */
public final class Verifier {

    private Verifier() {
    }

    /** What the new database must contain: its node ids, and the totals of everything that was exported. */
    public record Expected(IdSet ids, long nodes, long relationships, long nodeFingerprint, long relationshipFingerprint) {

        public static Expected of(IdSet ids, CsvExporter.Export... exports) {
            long nodes = 0;
            long relationships = 0;
            long nodeHash = 0;
            long relationshipHash = 0;
            for (CsvExporter.Export export : exports) {
                nodes += export.nodes();
                relationships += export.relationships();
                nodeHash += export.nodeFingerprint();
                relationshipHash += export.relationshipFingerprint();
            }
            return new Expected(ids, nodes, relationships, nodeHash, relationshipHash);
        }
    }

    /**
     * Reads every expected node and its relationships back, on {@code workers} threads, and compares them with what the
     * export wrote. The database must hold exactly those nodes and relationships: its own counts are compared as well, so
     * a node or relationship nobody asked for cannot hide.
     */
    public static void verify(CsvExporter.Source built, Expected expected, int batchSize, int workers, DryRunProgress progress) {
        long inDatabase = built.nodeCount();
        if (inDatabase != expected.nodes()) {
            throw new BuildException("The new database has " + inDatabase + " nodes but " + expected.nodes()
                    + " were exported. It has been discarded.");
        }
        long relationshipsInDatabase = built.relationshipCount();
        if (relationshipsInDatabase != expected.relationships()) {
            throw new BuildException("The new database has " + relationshipsInDatabase + " relationships but "
                    + expected.relationships() + " were exported. It has been discarded.");
        }

        Scanner scanner = new Scanner(built, expected.ids(), List.of(CsvExporter.Rule.out(expected.ids())), batchSize, workers, progress);
        List<Scanner.Part<long[]>> parts = scanner.run(worker -> new Scanner.Sink<long[]>() {
            private long nodes;
            private long nodeHash;
            private long relationships;
            private long relationshipHash;

            @Override
            public void accept(long id, List<String> labels, Map<String, Value> props) {
                nodeHash += Fingerprint.node(id, labels, props);
                nodes++;
            }

            @Override
            public void accept(long start, long end, String type, long relationshipId, Map<String, Value> props) {
                relationshipHash += Fingerprint.relationship(start, end, type, props);
                relationships++;
            }

            @Override
            public long[] finish() {
                return new long[] {nodes, nodeHash, relationships, relationshipHash};
            }
        });
        long nodes = 0;
        long nodeHash = 0;
        long relationships = 0;
        long relationshipHash = 0;
        long outside = 0;
        for (Scanner.Part<long[]> part : parts) {
            nodes += part.result()[0];
            nodeHash += part.result()[1];
            relationships += part.result()[2];
            relationshipHash += part.result()[3];
            outside += part.outside();
        }
        if (nodes != expected.nodes()) {
            throw new BuildException("The new database has " + nodes + " of the " + expected.nodes()
                    + " nodes that were exported. It has been discarded.");
        }
        if (nodeHash != expected.nodeFingerprint()) {
            throw new BuildException("The nodes of the new database differ from the ones that were exported (labels or "
                    + "properties changed on the way). It has been discarded.");
        }
        if (outside > 0 || relationships != expected.relationships()) {
            throw new BuildException("The new database has " + (relationships + outside) + " relationships between these nodes "
                    + "but " + expected.relationships() + " were exported. It has been discarded.");
        }
        if (relationshipHash != expected.relationshipFingerprint()) {
            throw new BuildException("The relationships of the new database differ from the ones that were exported. "
                    + "It has been discarded.");
        }
    }
}
