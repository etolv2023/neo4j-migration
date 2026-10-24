package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.build.FastReader;
import com.etolv.neo4jtool.engine.build.IdSet;
import com.etolv.neo4jtool.engine.build.RenumberPlanner;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.ConflictSample;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.Gate;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.IdRun;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.LabelCount;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.NodeComparison;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.NodeSample;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipAnalysis;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.OrgCount;
import com.etolv.neo4jtool.engine.selection.CypherQuery;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import com.etolv.neo4jtool.engine.selection.SelectionQueryBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

/**
 * Works out what would happen if the selected nodes of one database were put into another, keeping their ids.
 * It only reads. Without a target it reports on the selection itself, which is what a split needs.
 */
public final class DryRunService {

    static final byte MISSING = 1;
    static final byte SAME = 2;
    static final byte CONFLICT = 3;

    private static final String NO_LABELS = "(no labels)";
    private static final int TOP_RUNS = 10;

    /**
     * @param source the database the nodes are taken from
     * @param target the database they would be put into, or null to analyse the selection alone
     */
    public DryRunReport run(Neo4jClient source, Neo4jClient target, SelectionConfig config,
                            DryRunOptions options, DryRunProgress progress) {
        return run(source, null, target, null, config, options, progress);
    }

    /**
     * @param sourceFast reads the source with the kernel when not null (used to search the properties for old ids)
     * @param targetFast the same for the target
     */
    public DryRunReport run(Neo4jClient source, FastReader sourceFast, Neo4jClient target, FastReader targetFast,
                            SelectionConfig config, DryRunOptions options, DryRunProgress progress) {
        Instant started = Instant.now();
        String sourceVersion = source.serverVersion();
        String targetVersion = target == null ? null : target.serverVersion();

        progress.phase("Reading the selected nodes from the source");
        SelectedNodes nodes = loadSelected(source, config, progress);

        NodeComparison comparison = null;
        if (target != null) {
            progress.phase("Comparing with the target");
            comparison = compare(target, nodes, config.orgProperty(), options, progress);
        }

        RelationshipAnalysis relationships = null;
        if (options.includeRelationships()) {
            progress.phase("Analysing relationships");
            relationships = new RelationshipAnalyzer(source, target, nodes, config.orgProperty(), options, progress)
                    .analyze();
        }

        RenumberPlanner.Plan renumbering = null;
        if (comparison != null && comparison.conflict() > 0 && options.renumber().enabled()) {
            renumbering = RenumberPlanner.plan(source, sourceFast, target, targetFast, config, idsWith(nodes, CONFLICT),
                    idsWith(nodes, MISSING), options.renumber(), options.batchSize(), options.workers(), options.sampleSize(), progress);
        }

        List<String> reasons = new ArrayList<>();
        Gate gate = gate(comparison, relationships, renumbering, options, reasons);
        Instant finished = Instant.now();
        return new DryRunReport(started.toString(), finished.toString(),
                Duration.between(started, finished).toMillis(),
                sourceVersion, source.database(),
                targetVersion, target == null ? null : target.database(),
                config, config.selectsEverything(),
                nodes.size(), byLabel(nodes, nodes.countsByLabelSet()), byOrg(nodes, nodes.countsByOrg()),
                comparison, relationships, gate, reasons, renumbering);
    }

    private SelectedNodes loadSelected(Neo4jClient source, SelectionConfig config, DryRunProgress progress) {
        CypherQuery query = SelectionQueryBuilder.nodeStream(config);
        SelectedNodes nodes = new SelectedNodes();
        try (Session session = source.readSession()) {
            Result result = session.run(query.text(), query.params());
            long count = 0;
            while (result.hasNext()) {
                Record r = result.next();
                nodes.add(r.get("id").asLong(), NodeRows.sortedLabels(r.get("labels")), NodeRows.orgValue(r.get("org")));
                if ((++count & 0xFFFFF) == 0) {
                    progress.progress(count, -1);
                }
            }
        }
        nodes.seal();
        return nodes;
    }

    private NodeComparison compare(Neo4jClient target, SelectedNodes nodes, String orgProperty,
                                   DryRunOptions options, DryRunProgress progress) {
        int n = nodes.size();
        long[] missingByLabelSet = new long[nodes.labelSetCount()];
        long[] conflictByLabelSet = new long[nodes.labelSetCount()];
        long[] missingByOrg = new long[nodes.orgCount()];
        long[] conflictByOrg = new long[nodes.orgCount()];
        long missing = 0;
        long same = 0;
        long conflict = 0;
        List<NodeSample> missingSamples = new ArrayList<>();
        List<ConflictSample> conflictSamples = new ArrayList<>();
        String query = NodeRows.byIdQuery(orgProperty);

        try (Session session = target.readSession()) {
            for (int from = 0; from < n; from += options.batchSize()) {
                int to = Math.min(n, from + options.batchSize());
                List<ConflictSample> batchConflicts = new ArrayList<>();
                Result result = session.run(query, NodeRows.idsParam(nodes.idsSlice(from, to)));
                while (result.hasNext()) {
                    Record r = result.next();
                    int i = nodes.indexOf(r.get("id").asLong());
                    if (i < from || i >= to) {
                        continue;
                    }
                    List<String> targetLabels = NodeRows.sortedLabels(r.get("labels"));
                    Object targetOrg = NodeRows.orgValue(r.get("org"));
                    boolean equal = nodes.findLabelSet(targetLabels) == nodes.labelSetIdx(i)
                            && nodes.findOrg(targetOrg) == nodes.orgIdx(i);
                    if (equal) {
                        nodes.setStatus(i, SAME);
                        same++;
                    } else {
                        nodes.setStatus(i, CONFLICT);
                        conflict++;
                        conflictByLabelSet[nodes.labelSetIdx(i)]++;
                        conflictByOrg[nodes.orgIdx(i)]++;
                        batchConflicts.add(new ConflictSample(nodes.id(i), nodes.labelsOf(i), nodes.orgOf(i),
                                targetLabels, targetOrg));
                    }
                }
                batchConflicts.sort(Comparator.comparingLong(ConflictSample::id));
                for (ConflictSample sample : batchConflicts) {
                    if (conflictSamples.size() < options.sampleSize()) {
                        conflictSamples.add(sample);
                    }
                }
                for (int i = from; i < to; i++) {
                    if (nodes.status(i) == SelectedNodes.UNSET) {
                        nodes.setStatus(i, MISSING);
                        missing++;
                        missingByLabelSet[nodes.labelSetIdx(i)]++;
                        missingByOrg[nodes.orgIdx(i)]++;
                        if (missingSamples.size() < options.sampleSize()) {
                            missingSamples.add(new NodeSample(nodes.id(i), nodes.labelsOf(i), nodes.orgOf(i)));
                        }
                    }
                }
                progress.progress(to, n);
            }
        }

        PriorityQueue<IdRun> largest = new PriorityQueue<>(Comparator.comparingLong(IdRun::length));
        long runCount = missingRuns(nodes, largest);
        List<IdRun> runs = new ArrayList<>(largest);
        runs.sort(Comparator.comparingLong(IdRun::length).reversed().thenComparingLong(IdRun::fromId));

        return new NodeComparison(missing, same, conflict,
                byLabel(nodes, missingByLabelSet), byLabel(nodes, conflictByLabelSet),
                byOrg(nodes, missingByOrg), byOrg(nodes, conflictByOrg),
                runCount, runs, missingSamples, conflictSamples);
    }

    /** Groups missing nodes with consecutive ids into runs, which shows where the lost data sits in the id space. */
    private long missingRuns(SelectedNodes nodes, PriorityQueue<IdRun> largest) {
        long runCount = 0;
        long start = -1;
        long previous = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.status(i) != MISSING) {
                continue;
            }
            long id = nodes.id(i);
            if (start >= 0 && id == previous + 1) {
                previous = id;
                continue;
            }
            if (start >= 0) {
                runCount++;
                keepLargest(largest, new IdRun(start, previous, previous - start + 1));
            }
            start = id;
            previous = id;
        }
        if (start >= 0) {
            runCount++;
            keepLargest(largest, new IdRun(start, previous, previous - start + 1));
        }
        return runCount;
    }

    private static void keepLargest(PriorityQueue<IdRun> largest, IdRun run) {
        largest.add(run);
        if (largest.size() > TOP_RUNS) {
            largest.poll();
        }
    }

    private static IdSet idsWith(SelectedNodes nodes, byte status) {
        LongArrayList ids = new LongArrayList();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.status(i) == status) {
                ids.add(nodes.id(i));
            }
        }
        return IdSet.of(ids);
    }

    private static Gate gate(NodeComparison comparison, RelationshipAnalysis relationships, RenumberPlanner.Plan renumbering,
                             DryRunOptions options, List<String> reasons) {
        Gate gate = Gate.CLEAR;
        if (comparison != null && comparison.conflict() > 0) {
            if (options.renumber().enabled()) {
                reasons.add(comparison.conflict() + " selected ids are already used by a different node on the target; those nodes "
                        + "will be moved with new ids (" + (renumbering == null ? "" : "from " + renumbering.firstNewId() + " ") + "see 'New ids')");
                gate = Gate.WARN;
            } else {
                reasons.add(comparison.conflict() + " selected ids are already used by a different node on the target");
                gate = Gate.BLOCKED;
            }
        }
        if (renumbering != null && renumbering.references() != null) {
            long values = renumbering.references().valuesToChange(renumbering.includeNewerNodes());
            if (values > 0) {
                reasons.add(values + " property values hold an old id and will be replaced by the new one, in "
                        + renumbering.references().nodesToChange(renumbering.includeNewerNodes()) + " nodes");
            }
            if (!renumbering.includeNewerNodes() && renumbering.references().newer().nodesWithReferences() > 0) {
                reasons.add(renumbering.references().newer().nodesWithReferences() + " nodes of the company that were made after the "
                        + "backup hold an old id; they are skipped (see 'New ids')");
            }
        }
        if (relationships != null) {
            if (relationships.conflict() > 0) {
                reasons.add(relationships.conflict()
                        + " relationships point to an id that is used by a different node on the target");
                gate = gate == Gate.BLOCKED ? gate : Gate.WARN;
            }
            if (relationships.dangling() > 0) {
                reasons.add(relationships.dangling() + " relationships point to a node that does not exist on the target");
                gate = gate == Gate.BLOCKED ? gate : Gate.WARN;
            }
            if (relationships.boundary() > 0) {
                reasons.add(relationships.boundary() + " relationships leave the selection and would be dropped");
                gate = gate == Gate.BLOCKED ? gate : Gate.WARN;
            }
        }
        return gate;
    }

    /** A node with several labels counts once under each of its labels. */
    private static List<LabelCount> byLabel(SelectedNodes nodes, long[] perLabelSet) {
        Map<String, Long> totals = new TreeMap<>();
        for (int i = 0; i < perLabelSet.length; i++) {
            if (perLabelSet[i] == 0) {
                continue;
            }
            List<String> labels = nodes.labelSet(i);
            if (labels.isEmpty()) {
                totals.merge(NO_LABELS, perLabelSet[i], Long::sum);
            }
            for (String label : labels) {
                totals.merge(label, perLabelSet[i], Long::sum);
            }
        }
        List<LabelCount> result = new ArrayList<>();
        totals.forEach((label, count) -> result.add(new LabelCount(label, count)));
        result.sort(Comparator.comparingLong(LabelCount::count).reversed().thenComparing(LabelCount::label));
        return result;
    }

    private static List<OrgCount> byOrg(SelectedNodes nodes, long[] perOrg) {
        List<OrgCount> result = new ArrayList<>();
        for (int i = 0; i < perOrg.length; i++) {
            if (perOrg[i] > 0) {
                result.add(new OrgCount(nodes.org(i), perOrg[i]));
            }
        }
        result.sort(Comparator.comparingLong(OrgCount::count).reversed());
        return result;
    }
}
