package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipAnalysis;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipSample;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipTypeCounts;
import com.etolv.neo4jtool.engine.dryrun.NodeRows.NodeRow;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

/**
 * Looks at the relationships of the nodes that matter for a run and decides what would happen to each:
 * with a target, the relationships of the nodes missing on the target; without one, those of all selected nodes.
 */
final class RelationshipAnalyzer {

    private static final String QUERY = "MATCH (a)-[r]-(b) WHERE id(a) IN $ids "
            + "RETURN id(r) AS rid, type(r) AS type, id(startNode(r)) AS s, id(endNode(r)) AS e, id(a) AS a";

    private enum Kind { INTERNAL, RESOLVED, CONFLICT, DANGLING, BOUNDARY }

    private record Pending(long id, String type, long start, long end, long other) {
    }

    private static final class Counter {
        long internal;
        long resolved;
        long conflict;
        long dangling;
        long boundary;
    }

    private final Neo4jClient source;
    private final Neo4jClient target;
    private final SelectedNodes nodes;
    private final String orgProperty;
    private final DryRunOptions options;
    private final DryRunProgress progress;

    private final Map<String, Counter> byType = new TreeMap<>();
    private final List<RelationshipSample> danglingSamples = new ArrayList<>();
    private final List<RelationshipSample> conflictSamples = new ArrayList<>();
    private final List<RelationshipSample> boundarySamples = new ArrayList<>();
    private long total;

    RelationshipAnalyzer(Neo4jClient source, Neo4jClient target, SelectedNodes nodes, String orgProperty,
                         DryRunOptions options, DryRunProgress progress) {
        this.source = source;
        this.target = target;
        this.nodes = nodes;
        this.orgProperty = orgProperty;
        this.options = options;
        this.progress = progress;
    }

    RelationshipAnalysis analyze() {
        LongArrayList scope = new LongArrayList();
        for (int i = 0; i < nodes.size(); i++) {
            if (inScope(i)) {
                scope.add(nodes.id(i));
            }
        }
        // Relationship queries return many rows per node, so batches are smaller than for node lookups.
        int batch = Math.max(100, options.batchSize() / 4);
        for (int from = 0; from < scope.size(); from += batch) {
            int to = Math.min(scope.size(), from + batch);
            processBatch(scope.subList(from, to).toLongArray());
            progress.progress(to, scope.size());
        }
        return result();
    }

    private boolean inScope(int index) {
        return target == null || nodes.status(index) == DryRunService.MISSING;
    }

    private void processBatch(long[] batchIds) {
        List<Pending> pending = new ArrayList<>();
        LongOpenHashSet selfLoopsSeen = new LongOpenHashSet();
        try (Session session = source.readSession()) {
            Result result = session.run(QUERY, NodeRows.idsParam(batchIds));
            while (result.hasNext()) {
                Record r = result.next();
                long id = r.get("rid").asLong();
                long start = r.get("s").asLong();
                long end = r.get("e").asLong();
                long a = r.get("a").asLong();
                String type = r.get("type").asString();
                long other = start == a ? end : start;
                if (other == a && !selfLoopsSeen.add(id)) {
                    continue;
                }
                int otherIndex = nodes.indexOf(other);
                boolean otherInScope = otherIndex >= 0 && inScope(otherIndex);
                // A relationship between two nodes in scope shows up once from each end; count it from its start only.
                if (otherInScope && start != a) {
                    continue;
                }
                if (otherInScope) {
                    record(Kind.INTERNAL, id, type, start, end);
                } else if (otherIndex >= 0 && target != null) {
                    // The other end is selected but not restored: it is the same node as on the target, or a clash.
                    record(nodes.status(otherIndex) == DryRunService.SAME ? Kind.RESOLVED : Kind.CONFLICT,
                            id, type, start, end);
                } else if (target == null) {
                    record(Kind.BOUNDARY, id, type, start, end);
                } else {
                    pending.add(new Pending(id, type, start, end, other));
                }
            }
        }
        resolvePending(pending);
    }

    /** The other end is not part of the selection, so compare what the source and the target hold at its id. */
    private void resolvePending(List<Pending> pending) {
        if (pending.isEmpty()) {
            return;
        }
        LongOpenHashSet others = new LongOpenHashSet();
        pending.forEach(p -> others.add(p.other()));
        long[] otherIds = others.toLongArray();
        Long2ObjectOpenHashMap<NodeRow> onTarget = NodeRows.fetch(target, otherIds, orgProperty);
        Long2ObjectOpenHashMap<NodeRow> onSource = NodeRows.fetch(source, otherIds, orgProperty);
        for (Pending p : pending) {
            NodeRow t = onTarget.get(p.other());
            Kind kind;
            if (t == null) {
                kind = Kind.DANGLING;
            } else {
                NodeRow s = onSource.get(p.other());
                kind = t.equals(s) ? Kind.RESOLVED : Kind.CONFLICT;
            }
            record(kind, p.id(), p.type(), p.start(), p.end());
        }
    }

    private void record(Kind kind, long id, String type, long start, long end) {
        total++;
        Counter c = byType.computeIfAbsent(type, k -> new Counter());
        switch (kind) {
            case INTERNAL -> c.internal++;
            case RESOLVED -> c.resolved++;
            case CONFLICT -> {
                c.conflict++;
                sample(conflictSamples, id, type, start, end);
            }
            case DANGLING -> {
                c.dangling++;
                sample(danglingSamples, id, type, start, end);
            }
            case BOUNDARY -> {
                c.boundary++;
                sample(boundarySamples, id, type, start, end);
            }
        }
    }

    private void sample(List<RelationshipSample> samples, long id, String type, long start, long end) {
        if (samples.size() < options.sampleSize()) {
            samples.add(new RelationshipSample(id, type, start, end));
        }
    }

    private RelationshipAnalysis result() {
        long internal = 0, resolved = 0, conflict = 0, dangling = 0, boundary = 0;
        List<RelationshipTypeCounts> types = new ArrayList<>();
        for (Map.Entry<String, Counter> e : byType.entrySet()) {
            Counter c = e.getValue();
            internal += c.internal;
            resolved += c.resolved;
            conflict += c.conflict;
            dangling += c.dangling;
            boundary += c.boundary;
            types.add(new RelationshipTypeCounts(e.getKey(), c.internal, c.resolved, c.conflict, c.dangling, c.boundary));
        }
        Comparator<RelationshipSample> byId = Comparator.comparingLong(RelationshipSample::id);
        danglingSamples.sort(byId);
        conflictSamples.sort(byId);
        boundarySamples.sort(byId);
        return new RelationshipAnalysis(target == null ? "selected-nodes" : "missing-nodes", total, internal,
                resolved, conflict, dangling, boundary, types, danglingSamples, conflictSamples, boundarySamples);
    }
}
