package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.build.RenumberPlanner;
import com.etolv.neo4jtool.engine.schema.SchemaSnapshot.OrgCount;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.util.List;

/**
 * Result of a dry run. Source is the database the data comes from (for example a backup);
 * target is the database it would be put into (for example the live one). Nothing is written.
 */
public record DryRunReport(
        String startedAt,
        String finishedAt,
        long durationMillis,
        String sourceVersion,
        String sourceDatabase,
        String targetVersion,
        String targetDatabase,
        SelectionConfig selection,
        boolean selectsEverything,
        long selectedNodes,
        List<LabelCount> selectedByLabel,
        List<OrgCount> selectedByOrg,
        NodeComparison nodes,
        RelationshipAnalysis relationships,
        Gate gate,
        List<String> gateReasons,
        RenumberPlanner.Plan renumbering) {

    public DryRunReport(String startedAt, String finishedAt, long durationMillis, String sourceVersion, String sourceDatabase,
                        String targetVersion, String targetDatabase, SelectionConfig selection, boolean selectsEverything,
                        long selectedNodes, List<LabelCount> selectedByLabel, List<OrgCount> selectedByOrg, NodeComparison nodes,
                        RelationshipAnalysis relationships, Gate gate, List<String> gateReasons) {
        this(startedAt, finishedAt, durationMillis, sourceVersion, sourceDatabase, targetVersion, targetDatabase, selection,
                selectsEverything, selectedNodes, selectedByLabel, selectedByOrg, nodes, relationships, gate, gateReasons, null);
    }

    /**
     * BLOCKED: some selected ids are already used by a different node on the target and renumbering is off. With it on, such
     * nodes get new ids and the gate is at most WARN.
     */
    public enum Gate { CLEAR, WARN, BLOCKED }

    public record LabelCount(String label, long count) {
    }

    /** Comparison of selected source nodes with the node (if any) that has the same id on the target. */
    public record NodeComparison(
            long missing,
            long same,
            long conflict,
            List<LabelCount> missingByLabel,
            List<LabelCount> conflictByLabel,
            List<OrgCount> missingByOrg,
            List<OrgCount> conflictByOrg,
            long missingRunCount,
            List<IdRun> largestMissingRuns,
            List<NodeSample> missingSamples,
            List<ConflictSample> conflictSamples) {
    }

    /** A run of consecutive ids. */
    public record IdRun(long fromId, long toId, long length) {
    }

    public record NodeSample(long id, List<String> labels, Object org) {
    }

    public record ConflictSample(long id, List<String> sourceLabels, Object sourceOrg,
                                 List<String> targetLabels, Object targetOrg) {
    }

    /**
     * Relationships of the nodes that would be restored (nodes missing on the target), or of all selected nodes when
     * there is no target.
     *
     * @param scope "missing-nodes" or "selected-nodes"
     */
    public record RelationshipAnalysis(
            String scope,
            long total,
            long internal,
            long resolved,
            long conflict,
            long dangling,
            long boundary,
            List<RelationshipTypeCounts> byType,
            List<RelationshipSample> danglingSamples,
            List<RelationshipSample> conflictSamples,
            List<RelationshipSample> boundarySamples,
            List<LeftOutGroup> leftOut,
            long leftOutNotListed) {
    }

    /** Why a relationship of a moved node is not moved with it. */
    public enum LeftOutReason {
        /** The other node carries the value of an organisation that is not part of the selection. */
        OTHER_ORGANISATION,
        /** The other node has no organisation and is not part of the selection. */
        NO_ORGANISATION,
        /** The other node does not exist on the target. */
        NOT_ON_TARGET,
        /** The id of the other node is used by a different node on the target. */
        DIFFERENT_ON_TARGET
    }

    /**
     * Relationships that are left out, grouped by what they connect: the labels of the node they start at and of the
     * node they end at, their type and the reason. {@code otherOrgs} are some of the organisation values of the node
     * that is not moved; {@code samples} are a few of the relationships.
     */
    public record LeftOutGroup(LeftOutReason reason, String type, List<String> fromLabels, List<String> toLabels,
                               long count, List<Object> otherOrgs, List<RelationshipSample> samples) {
    }

    /**
     * internal: both ends are in the restored set. resolved: the other end already exists on the target as the same
     * node. conflict: the other end's id is used by a different node on the target. dangling: the other end does not
     * exist on the target. boundary: (no target) the other end is outside the selection.
     */
    public record RelationshipTypeCounts(String type, long internal, long resolved, long conflict,
                                         long dangling, long boundary) {
    }

    public record RelationshipSample(long id, String type, long startNode, long endNode) {
    }
}
