package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Puts groups of entities that have compatible property columns into shared layouts.
 *
 * <p>The importer opens every layout (a header file and its parts) on its own, and that costs it about a third of a second
 * each, before it reads a single row: a database with ten thousand label sets that differ in a few properties spends
 * more than half an hour there. Groups whose columns do not contradict each other (no property has two types) can share a
 * header made of all their properties: a row leaves the cells of the properties it does not have empty, and an empty cell
 * is no property at all, as it already is for the nodes of one group that lack a property.
 *
 * <p>A header never gets wider than {@code maxColumns}, so that a row does not carry thousands of empty cells, and a group
 * with a property of several types keeps its own layouts (one per combination of types), exactly as without merging.
 */
final class LayoutMerger {

    /** What merging did, for the log. */
    record Result(int groups, int merged, int layouts, int widest) {

        String describe() {
            return groups + " groups of entities, " + merged + " of them share layouts, " + layouts + " layouts for those, the widest has "
                    + widest + " columns";
        }
    }

    private static final class Layout {
        private final TreeMap<String, String> types = new TreeMap<>();
        private final List<GroupPlan> groups = new ArrayList<>();
    }

    private LayoutMerger() {
    }

    /**
     * @param plans      frozen plans, one per group
     * @param maxColumns the most property columns a shared layout may have; 0 or less leaves every group as it is
     */
    static Result merge(Collection<GroupPlan> plans, int maxColumns) {
        if (maxColumns <= 0) {
            return new Result(plans.size(), 0, 0, 0);
        }
        // the biggest groups first, so that their layouts are the ones the small groups join
        List<GroupPlan> candidates = new ArrayList<>(plans.stream().filter(plan -> plan.conflictKeys().isEmpty()).toList());
        candidates.sort(Comparator.comparingLong(GroupPlan::rows).reversed());
        List<Layout> layouts = new ArrayList<>();
        for (GroupPlan plan : candidates) {
            Map<String, String> own = plan.ownPlainTypes();
            Layout best = null;
            int bestAdded = Integer.MAX_VALUE;
            for (Layout layout : layouts) {
                int added = addedColumns(layout, own);
                if (added >= 0 && layout.types.size() + added <= maxColumns && added < bestAdded) {
                    best = layout;
                    bestAdded = added;
                }
            }
            if (best == null) {
                best = new Layout();
                layouts.add(best);
            }
            best.types.putAll(own);
            best.groups.add(plan);
        }
        int merged = 0;
        int widest = 0;
        for (Layout layout : layouts) {
            List<String> keys = List.copyOf(layout.types.keySet());
            Map<String, String> types = Map.copyOf(layout.types);
            layout.groups.forEach(plan -> plan.useLayout(keys, types));
            merged += layout.groups.size();
            widest = Math.max(widest, keys.size());
        }
        return new Result(plans.size(), merged, layouts.size(), widest);
    }

    /** How many columns the group would add to the layout, or -1 when a property has another type there. */
    private static int addedColumns(Layout layout, Map<String, String> own) {
        int added = 0;
        for (Map.Entry<String, String> column : own.entrySet()) {
            String type = layout.types.get(column.getKey());
            if (type == null) {
                added++;
            } else if (!type.equals(column.getValue())) {
                return -1;
            }
        }
        return added;
    }
}
