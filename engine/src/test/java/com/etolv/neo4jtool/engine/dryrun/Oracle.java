package com.etolv.neo4jtool.engine.dryrun;

import com.etolv.neo4jtool.engine.dryrun.DryRunReport.Gate;
import com.etolv.neo4jtool.engine.dryrun.GraphDump.Node;
import com.etolv.neo4jtool.engine.dryrun.GraphDump.Rel;
import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The expected answer of a dry run, computed with plain loops over two complete dumps.
 * It is slow and obvious on purpose: it shares no code with the engine.
 */
final class Oracle {

    static final class RelCounts {
        long internal;
        long resolved;
        long conflict;
        long dangling;
        long boundary;
    }

    long selected;
    long missing;
    long same;
    long conflict;
    final Map<String, Long> selectedByLabel = new HashMap<>();
    final Map<Object, Long> selectedByOrg = new HashMap<>();
    final Map<String, Long> missingByLabel = new HashMap<>();
    final Map<String, Long> conflictByLabel = new HashMap<>();
    final Map<Object, Long> missingByOrg = new HashMap<>();
    final Map<Object, Long> conflictByOrg = new HashMap<>();
    final Set<Long> missingIds = new HashSet<>();
    long missingRuns;
    List<Long> topRunLengths = List.of();

    long relTotal;
    final Map<String, RelCounts> relByType = new TreeMap<>();
    final Set<Long> danglingRelIds = new HashSet<>();
    final Set<Long> conflictRelIds = new HashSet<>();
    final Set<Long> boundaryRelIds = new HashSet<>();
    Gate gate;

    /** @param target null means "no target": only the selection itself is analysed */
    static Oracle compute(GraphDump source, GraphDump target, SelectionConfig config) {
        Oracle o = new Oracle();
        Map<Long, Node> selected = new TreeMap<>();
        for (Node n : source.nodes.values()) {
            if (matches(config, n)) {
                selected.put(n.id(), n);
            }
        }
        o.selected = selected.size();
        Map<Long, Byte> status = new HashMap<>();
        for (Node n : selected.values()) {
            count(o.selectedByLabel, labelKeys(n));
            o.selectedByOrg.merge(n.org(), 1L, Long::sum);
            if (target == null) {
                continue;
            }
            Node t = target.nodes.get(n.id());
            if (t == null) {
                o.missing++;
                o.missingIds.add(n.id());
                status.put(n.id(), (byte) 1);
                count(o.missingByLabel, labelKeys(n));
                o.missingByOrg.merge(n.org(), 1L, Long::sum);
            } else if (sameSignature(n, t)) {
                o.same++;
                status.put(n.id(), (byte) 2);
            } else {
                o.conflict++;
                status.put(n.id(), (byte) 3);
                count(o.conflictByLabel, labelKeys(n));
                o.conflictByOrg.merge(n.org(), 1L, Long::sum);
            }
        }
        o.runs();

        Set<Long> scope = target == null ? selected.keySet() : o.missingIds;
        for (Rel r : source.rels) {
            boolean startIn = scope.contains(r.start());
            boolean endIn = scope.contains(r.end());
            if (!startIn && !endIn) {
                continue;
            }
            o.relTotal++;
            RelCounts c = o.relByType.computeIfAbsent(r.type(), k -> new RelCounts());
            if (startIn && endIn) {
                c.internal++;
                continue;
            }
            long other = startIn ? r.end() : r.start();
            if (target == null) {
                c.boundary++;
                o.boundaryRelIds.add(r.id());
            } else if (selected.containsKey(other)) {
                if (status.get(other) == 2) {
                    c.resolved++;
                } else {
                    c.conflict++;
                    o.conflictRelIds.add(r.id());
                }
            } else {
                Node t = target.nodes.get(other);
                if (t == null) {
                    c.dangling++;
                    o.danglingRelIds.add(r.id());
                } else if (sameSignature(source.nodes.get(other), t)) {
                    c.resolved++;
                } else {
                    c.conflict++;
                    o.conflictRelIds.add(r.id());
                }
            }
        }
        o.gate = o.gate();
        return o;
    }

    private void runs() {
        List<Long> sorted = new ArrayList<>(missingIds);
        sorted.sort(Long::compare);
        List<Long> lengths = new ArrayList<>();
        long length = 0;
        long previous = -2;
        for (long id : sorted) {
            if (length > 0 && id == previous + 1) {
                length++;
            } else {
                if (length > 0) {
                    lengths.add(length);
                }
                length = 1;
            }
            previous = id;
        }
        if (length > 0) {
            lengths.add(length);
        }
        missingRuns = lengths.size();
        lengths.sort(Comparator.reverseOrder());
        topRunLengths = lengths.subList(0, Math.min(10, lengths.size()));
    }

    private Gate gate() {
        Gate gate = Gate.CLEAR;
        if (conflict > 0) {
            return Gate.BLOCKED;
        }
        for (RelCounts c : relByType.values()) {
            if (c.conflict > 0 || c.dangling > 0 || c.boundary > 0) {
                gate = Gate.WARN;
            }
        }
        return gate;
    }

    static boolean matches(SelectionConfig c, Node n) {
        if (!c.labels().isEmpty() && c.labels().stream().noneMatch(n.labels()::contains)) {
            return false;
        }
        if (!c.orgIds().isEmpty() && (n.org() == null || !c.orgIds().contains(n.org()))) {
            return false;
        }
        if (!c.ids().isEmpty() || !c.idRanges().isEmpty()) {
            boolean inIds = c.ids().contains(n.id());
            boolean inRange = c.idRanges().stream().anyMatch(r -> n.id() >= r.get(0) && n.id() <= r.get(1));
            if (!inIds && !inRange) {
                return false;
            }
        }
        return !c.excludeIds().contains(n.id());
    }

    private static boolean sameSignature(Node a, Node b) {
        return a.labels().equals(b.labels()) && java.util.Objects.equals(a.org(), b.org());
    }

    private static List<String> labelKeys(Node n) {
        return n.labels().isEmpty() ? List.of("(no labels)") : n.labels();
    }

    private static void count(Map<String, Long> map, List<String> keys) {
        for (String key : keys) {
            map.merge(key, 1L, Long::sum);
        }
    }
}
