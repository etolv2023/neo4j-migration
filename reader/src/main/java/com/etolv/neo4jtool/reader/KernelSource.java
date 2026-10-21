package com.etolv.neo4jtool.reader;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.CsvExporter;
import com.etolv.neo4jtool.engine.build.IdSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.internal.kernel.api.TokenSet;
import org.neo4j.kernel.api.ExecutionContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.storageengine.api.RelationshipSelection;

/**
 * Reads nodes and relationships straight from the store with the kernel's cursors: no query planner, no Bolt, no
 * serialization. One read transaction serves every worker; each worker has its own cursors and execution context.
 */
final class KernelSource implements CsvExporter.Source, AutoCloseable {

    private final Transaction transaction;
    private final KernelTransaction kernel;
    private final AutoCloseable statement;
    private final String[] labelNames;
    private final String[] typeNames;
    private final String[] keyNames;

    KernelSource(Store store) {
        this.transaction = store.database().beginTx();
        this.kernel = ((InternalTransaction) transaction).kernelTransaction();
        this.statement = kernel.acquireStatement();      // execution contexts can only be made while a statement is open
        TokenRead tokens = kernel.tokenRead();
        try {
            labelNames = names(tokens.labelsGetAllTokens());
            typeNames = names(tokens.relationshipTypesGetAllTokens());
            keyNames = names(tokens.propertyKeyGetAllTokens());
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private static String[] names(java.util.Iterator<org.neo4j.token.api.NamedToken> tokens) {
        List<org.neo4j.token.api.NamedToken> all = new ArrayList<>();
        tokens.forEachRemaining(all::add);
        int max = all.stream().mapToInt(org.neo4j.token.api.NamedToken::id).max().orElse(-1);
        String[] names = new String[max + 1];
        for (org.neo4j.token.api.NamedToken token : all) {
            names[token.id()] = token.name();
        }
        return names;
    }

    KernelTransaction kernel() {
        return kernel;
    }

    String label(int id) {
        return labelNames[id];
    }

    String propertyKey(int id) {
        return keyNames[id];
    }

    int labelId(String name) {
        return kernel.tokenRead().nodeLabel(name);
    }

    int propertyKeyId(String name) {
        return kernel.tokenRead().propertyKey(name);
    }

    @Override
    public long nodeCount() {
        return kernel.dataRead().countsForNode(org.neo4j.token.api.TokenConstants.ANY_LABEL);
    }

    @Override
    public long relationshipCount() {
        return kernel.dataRead().countsForRelationship(org.neo4j.token.api.TokenConstants.ANY_LABEL, org.neo4j.token.api.TokenConstants.ANY_RELATIONSHIP_TYPE, org.neo4j.token.api.TokenConstants.ANY_LABEL);
    }

    @Override
    public CsvExporter.Reader open() {
        return new KernelReader();
    }

    @Override
    public void close() {
        try {
            statement.close();
        } catch (Exception ignored) {
            // read only: nothing to lose
        }
        transaction.rollback();
        transaction.close();
    }

    /** The cursors of one worker. */
    private final class KernelReader implements CsvExporter.Reader {

        private final ExecutionContext context = kernel.createExecutionContext();
        private final NodeCursor nodes = context.cursors().allocateNodeCursor(context.cursorContext());
        private final PropertyCursor properties = context.cursors().allocatePropertyCursor(context.cursorContext(), context.memoryTracker());
        private final RelationshipTraversalCursor relationships = context.cursors().allocateRelationshipTraversalCursor(context.cursorContext());

        @Override
        public long read(long[] ids, List<CsvExporter.Rule> rules, IdSet exported, CsvExporter.NodeVisitor nodeVisitor,
                         CsvExporter.RelationshipVisitor relationshipVisitor) {
            long outside = 0;
            RelationshipSelection[] selections = new RelationshipSelection[rules.size()];
            for (int i = 0; i < selections.length; i++) {
                selections[i] = RelationshipSelection.selection(rules.get(i).outgoing() ? Direction.OUTGOING : Direction.INCOMING);
            }
            for (long id : ids) {
                context.dataRead().singleNode(id, nodes);
                if (!nodes.next()) {
                    throw new BuildException("Node " + id + " is gone from the source. "
                            + "The source changed while it was being read; run the split again.");
                }
                nodeVisitor.accept(id, labelsOf(nodes.labels()), propertiesOf(nodes, properties));
                for (int r = 0; r < selections.length; r++) {
                    CsvExporter.Rule rule = rules.get(r);
                    nodes.relationships(relationships, selections[r]);
                    while (relationships.next()) {
                        long start = relationships.sourceNodeReference();
                        long end = relationships.targetNodeReference();
                        int what = rule.classify(start, end, exported);
                        if (what == CsvExporter.Rule.SKIP) {
                            continue;
                        }
                        if (what == CsvExporter.Rule.OUTSIDE) {
                            outside++;
                            continue;
                        }
                        relationships.properties(properties);
                        relationshipVisitor.accept(start, end, typeNames[relationships.type()], relationships.reference(),
                                propertiesOf(properties));
                    }
                }
            }
            return outside;
        }

        private List<String> labelsOf(TokenSet labels) {
            int n = labels.numberOfTokens();
            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                names.add(labelNames[labels.token(i)]);
            }
            return names;
        }

        private Map<String, org.neo4j.driver.Value> propertiesOf(NodeCursor node, PropertyCursor cursor) {
            node.properties(cursor);
            return propertiesOf(cursor);
        }

        private Map<String, org.neo4j.driver.Value> propertiesOf(PropertyCursor cursor) {
            Map<String, org.neo4j.driver.Value> map = new LinkedHashMap<>();
            while (cursor.next()) {
                map.put(keyNames[cursor.propertyKey()], KernelValues.toDriver(cursor.propertyValue()));
            }
            return map;
        }

        @Override
        public void close() {
            try {
                context.complete();
            } finally {
                context.close();
            }
        }
    }
}
