package com.etolv.neo4jtool.cli;

import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.NodeComparison;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport.RelationshipAnalysis;

/** Plain-text view of a dry-run report for the terminal. The JSON report holds everything. */
final class ReportText {

    private ReportText() {
    }

    static String render(DryRunReport r) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("Source: Neo4j %s (%s)%n", r.sourceVersion(), r.sourceDatabase()));
        if (r.targetVersion() != null) {
            out.append(String.format("Target: Neo4j %s (%s)%n", r.targetVersion(), r.targetDatabase()));
        } else {
            out.append("Target: none (split into a new database)\n");
        }
        out.append(String.format("Took %.1f s%n%n", r.durationMillis() / 1000.0));
        out.append(String.format("Selected nodes: %,d%s%n", r.selectedNodes(),
                r.selectsEverything() ? "  (the selection has no filter: every node)" : ""));
        r.selectedByLabel().forEach(l -> out.append(String.format("  %-30s %,12d%n", l.label(), l.count())));

        NodeComparison n = r.nodes();
        if (n != null) {
            out.append(String.format("%nAgainst the target:%n  missing (would be restored) %,12d%n  same node already there %,12d%n"
                    + "  id used by a DIFFERENT node %,11d%n", n.missing(), n.same(), n.conflict()));
            if (n.missingRunCount() > 0) {
                out.append(String.format("  missing ids form %,d runs; largest:", n.missingRunCount()));
                n.largestMissingRuns().stream().limit(5).forEach(run ->
                        out.append(String.format(" %d-%d (%,d)", run.fromId(), run.toId(), run.length())));
                out.append('\n');
            }
            n.conflictSamples().stream().limit(5).forEach(c -> out.append(String.format(
                    "  clash id %d: backup %s org=%s, live %s org=%s%n", c.id(), c.sourceLabels(), c.sourceOrg(),
                    c.targetLabels(), c.targetOrg())));
        }

        RelationshipAnalysis rel = r.relationships();
        if (rel != null) {
            out.append(String.format("%nRelationships (%s): %,d%n", rel.scope(), rel.total()));
            out.append(String.format("  internal %,d, resolved %,d, conflict %,d, dangling %,d, outside selection %,d%n",
                    rel.internal(), rel.resolved(), rel.conflict(), rel.dangling(), rel.boundary()));
        }

        out.append("\nResult: ").append(r.gate()).append('\n');
        r.gateReasons().forEach(reason -> out.append("  - ").append(reason).append('\n'));
        return out.toString();
    }
}
