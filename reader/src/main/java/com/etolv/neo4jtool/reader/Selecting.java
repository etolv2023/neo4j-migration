package com.etolv.neo4jtool.reader;

import com.etolv.neo4jtool.engine.build.BuildException;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.dryrun.DryRunProgress;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.PartitionedScan;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor;
import org.neo4j.kernel.api.ExecutionContext;
import org.neo4j.storageengine.api.RelationshipSelection;
import org.neo4j.values.storable.FloatingPointValue;
import org.neo4j.values.storable.IntegralValue;
import org.neo4j.values.storable.TextValue;
import org.neo4j.values.storable.ValueGroup;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * Finds the nodes a {@link SelectionConfig} selects: a scan of the node store cut into partitions that the workers take
 * one after the other, so the store is read once, in order, on all threads. The answer is the same as the Cypher query
 * of {@code SelectionQueryBuilder}; the integration tests compare the two.
 */
final class Selecting {

    private final KernelSource source;
    private final int workers;
    private final DryRunProgress progress;

    Selecting(KernelSource source, int workers, DryRunProgress progress) {
        this.source = source;
        this.workers = Math.max(1, workers);
        this.progress = progress;
    }

    /** Every node id. */
    IdSet all() {
        return select(SelectionConfig.everything());
    }

    IdSet select(SelectionConfig config) {
        Criteria criteria = new Criteria(config, source);
        if (criteria.nothingCanMatch()) {
            return IdSet.empty();
        }
        IdSet matched = scan(criteria);
        if (config.includeNeighbours() && !config.selectsEverything()) {
            progress.phase("Adding the connected nodes that have no organisation");
            IdSet neighbours = neighbours(matched, criteria);
            return IdSet.union(matched, neighbours);
        }
        return matched;
    }

    // ---- the scan ----

    private IdSet scan(Criteria criteria) {
        int partitions = Math.max(workers * 16, 16);
        PartitionedScan<NodeCursor> scan = source.kernel().dataRead().allNodesScan(partitions, source.kernel().cursorContext());
        AtomicInteger done = new AtomicInteger();
        int total = scan.getNumberOfPartitions();
        List<IdSet> parts = inWorkers(worker -> {
            IdSet.Builder found = new IdSet.Builder();
            try (ExecutionContext context = source.kernel().createExecutionContext()) {
                NodeCursor node = context.cursors().allocateNodeCursor(context.cursorContext());
                PropertyCursor property = context.cursors().allocatePropertyCursor(context.cursorContext(), context.memoryTracker());
                while (scan.reservePartition(node, context)) {
                    while (node.next()) {
                        if (criteria.matches(node, property)) {
                            found.add(node.nodeReference());
                        }
                    }
                    if (worker == 0) {
                        progress.progress(done.incrementAndGet(), total);
                    } else {
                        done.incrementAndGet();
                    }
                }
                context.complete();
            }
            return found.build();
        });
        return union(parts);
    }

    // ---- one step to the neighbours ----

    /** Nodes next to a selected node (any direction, any type) that carry no organisation and are not excluded. */
    private IdSet neighbours(IdSet selected, Criteria criteria) {
        long size = selected.size();
        List<IdSet> parts = inWorkers(worker -> {
            Roaring64Bitmap found = new Roaring64Bitmap();
            try (ExecutionContext context = source.kernel().createExecutionContext()) {
                NodeCursor node = context.cursors().allocateNodeCursor(context.cursorContext());
                NodeCursor other = context.cursors().allocateNodeCursor(context.cursorContext());
                PropertyCursor property = context.cursors().allocatePropertyCursor(context.cursorContext(), context.memoryTracker());
                RelationshipTraversalCursor relationships = context.cursors().allocateRelationshipTraversalCursor(context.cursorContext());
                long from = size * worker / workers;
                long to = size * (worker + 1) / workers;
                var ids = selected.iteratorFrom(from);
                for (long position = from; position < to; position++) {
                    context.dataRead().singleNode(ids.nextLong(), node);
                    if (!node.next()) {
                        continue;
                    }
                    node.relationships(relationships, RelationshipSelection.ALL_RELATIONSHIPS);
                    while (relationships.next()) {
                        long neighbour = relationships.otherNodeReference();
                        if (criteria.excluded(neighbour) || found.contains(neighbour)) {
                            continue;
                        }
                        context.dataRead().singleNode(neighbour, other);
                        if (other.next() && criteria.hasNoOrganisation(other, property)) {
                            found.add(neighbour);
                        }
                    }
                }
                context.complete();
            }
            IdSet.Builder ordered = new IdSet.Builder();
            var it = found.getLongIterator();
            while (it.hasNext()) {
                ordered.add(it.next());
            }
            return ordered.build();
        });
        return union(parts);
    }

    // ---- helpers ----

    private interface Work {
        IdSet run(int worker);
    }

    private List<IdSet> inWorkers(Work work) {
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "select-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<IdSet>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                int worker = w;
                futures.add(pool.submit(() -> work.run(worker)));
            }
            List<IdSet> results = new ArrayList<>();
            for (Future<IdSet> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("The selection was interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BuildException("The selection failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static IdSet union(List<IdSet> parts) {
        IdSet all = IdSet.empty();
        for (IdSet part : parts) {
            all = IdSet.union(all, part);
        }
        return all;
    }

    /** The selection criteria, resolved against the tokens of this store. */
    private static final class Criteria {

        private final SelectionConfig config;
        private final int orgKey;
        private final int[] labelIds;
        private final boolean hasOrgIds;
        private final LongOpenHashSet orgLongs = new LongOpenHashSet();
        private final Set<String> orgTexts = new HashSet<>();
        private final Set<Boolean> orgBooleans = new HashSet<>();
        private final List<Double> orgDoubles = new ArrayList<>();
        private final LongOpenHashSet ids;
        private final long[][] ranges;
        private final LongOpenHashSet excluded;
        private final boolean mustHaveId;
        private final boolean everything;

        Criteria(SelectionConfig config, KernelSource source) {
            this.config = config;
            this.orgKey = source.propertyKeyId(config.orgProperty());
            LongArrayList labels = new LongArrayList();
            for (String label : config.labels()) {
                int id = source.labelId(label);
                if (id >= 0) {
                    labels.add(id);
                }
            }
            this.labelIds = labels.longStream().mapToInt(l -> (int) l).toArray();
            this.hasOrgIds = !config.orgIds().isEmpty();
            for (Object value : config.orgIds()) {
                if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
                    orgLongs.add(((Number) value).longValue());
                } else if (value instanceof Double || value instanceof Float) {
                    orgDoubles.add(((Number) value).doubleValue());
                } else if (value instanceof Boolean b) {
                    orgBooleans.add(b);
                } else {
                    orgTexts.add(value.toString());
                }
            }
            this.ids = new LongOpenHashSet(config.ids());
            this.ranges = config.idRanges().stream().map(r -> new long[] {r.get(0), r.get(1)}).toArray(long[][]::new);
            this.excluded = new LongOpenHashSet(config.excludeIds());
            this.mustHaveId = !config.ids().isEmpty() || !config.idRanges().isEmpty();
            this.everything = config.selectsEverything();
        }

        /** True when a criterion names something this store does not have, so no node can match. */
        boolean nothingCanMatch() {
            if (!config.labels().isEmpty() && labelIds.length == 0) {
                return true;
            }
            return hasOrgIds && orgKey < 0;
        }

        boolean excluded(long id) {
            return excluded.contains(id);
        }

        boolean matches(NodeCursor node, PropertyCursor property) {
            long id = node.nodeReference();
            if (excluded.contains(id)) {
                return false;
            }
            if (mustHaveId && !inIds(id)) {
                return false;
            }
            if (!config.labels().isEmpty()) {
                boolean any = false;
                for (int label : labelIds) {
                    if (node.labels().contains(label)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            if (hasOrgIds) {
                node.properties(property);
                while (property.next()) {
                    if (property.propertyKey() == orgKey) {
                        return orgMatches(property.propertyValue());
                    }
                }
                return false;
            }
            return true;
        }

        private boolean inIds(long id) {
            if (ids.contains(id)) {
                return true;
            }
            for (long[] range : ranges) {
                if (id >= range[0] && id <= range[1]) {
                    return true;
                }
            }
            return false;
        }

        /** Cypher's {@code value IN list}: numbers equal by value, text by text, no mixing. */
        private boolean orgMatches(org.neo4j.values.storable.Value value) {
            if (value.valueGroup() == ValueGroup.TEXT) {
                return orgTexts.contains(((TextValue) value).stringValue());
            }
            if (value.valueGroup() == ValueGroup.BOOLEAN) {
                return orgBooleans.contains(((org.neo4j.values.storable.BooleanValue) value).booleanValue());
            }
            if (value instanceof IntegralValue integral) {
                long v = integral.longValue();
                if (orgLongs.contains(v)) {
                    return true;
                }
                for (double d : orgDoubles) {
                    if (d == v) {
                        return true;
                    }
                }
                return false;
            }
            if (value instanceof FloatingPointValue floating) {
                double d = floating.doubleValue();
                if (d == Math.rint(d) && Math.abs(d) < 9.0e18 && orgLongs.contains((long) d)) {
                    return true;
                }
                for (double wanted : orgDoubles) {
                    if (wanted == d) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** True when the node has no value in the organisation property, or an empty text: {@code m.org IS NULL OR m.org = ''}. */
        boolean hasNoOrganisation(NodeCursor node, PropertyCursor property) {
            if (orgKey < 0) {
                return true;
            }
            node.properties(property);
            while (property.next()) {
                if (property.propertyKey() == orgKey) {
                    org.neo4j.values.storable.Value value = property.propertyValue();
                    return value.valueGroup() == ValueGroup.TEXT && ((TextValue) value).stringValue().isEmpty();
                }
            }
            return true;
        }
    }
}
