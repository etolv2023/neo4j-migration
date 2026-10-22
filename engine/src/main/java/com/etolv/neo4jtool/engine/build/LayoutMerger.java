package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Puts groups of entities whose property columns do not contradict each other into shared layouts.
 *
 * <p>The importer reads a sample of every data file before it reads a row, and that costs it about a tenth of a second
 * each: a database with ten thousand layouts and thirty thousand files waits more than half an hour. A group (or one
 * combination of types of a group with conflicting types) can share a layout with others when no property has two types in
 * it. The header of the layout holds the properties of all of them; a row leaves the cells of the properties it does not
 * have empty, and an empty cell is no property at all, as it already is for the nodes of one group that lack a property.
 *
 * <p>A header never gets wider than {@code maxColumns}, so that a row does not carry thousands of empty cells. A group
 * wider than that keeps a layout of its own, and a group with conflicting types whose combinations are not all known keeps
 * one layout per combination, exactly as without sharing.
 */
final class LayoutMerger {

    /** What sharing did, for the log. */
    record Result(int groups, int items, int shared, int layouts, int widest) {

        String describe() {
            return groups + " groups of nodes (" + items + " combinations of property types), " + shared + " of them share "
                    + layouts + " layouts, the widest has " + widest + " columns";
        }
    }

    /** What is placed in a layout: a group, or one combination of types of a group with conflicting types. */
    private record Item(GroupPlan plan, String variantKey, Map<String, String> columns, long rows) {
    }

    private static final class Layout {
        private final TreeMap<String, String> types = new TreeMap<>();
        private final List<Item> items = new ArrayList<>();
    }

    private LayoutMerger() {
    }

    /**
     * @param plans      frozen plans, one per group, with their combinations of types when they have conflicts
     * @param maxColumns the most property columns a shared layout may have; 0 or less leaves every group as it is
     */
    static Result merge(Collection<GroupPlan> plans, int maxColumns) {
        if (maxColumns <= 0) {
            return new Result(plans.size(), 0, 0, 0, 0);
        }
        List<Item> items = new ArrayList<>();
        int groupsShared = 0;
        for (GroupPlan plan : plans) {
            Map<String, String> own = plan.ownPlainTypes();
            if (plan.conflictKeys().isEmpty()) {
                items.add(new Item(plan, null, own, plan.rows()));
                groupsShared++;
            } else if (plan.variantsComplete() && !plan.variants().isEmpty()) {
                plan.variants().forEach((key, variant) -> {
                    Map<String, String> columns = new TreeMap<>(own);
                    columns.putAll(variant.types());
                    items.add(new Item(plan, key, columns, variant.rows()));
                });
                groupsShared++;
            }
        }
        // the biggest first, so that their layouts are the ones the small ones join
        items.sort(Comparator.comparingLong(Item::rows).reversed());
        List<Layout> layouts = new ArrayList<>();
        for (Item item : items) {
            Layout best = null;
            int bestAdded = Integer.MAX_VALUE;
            for (Layout layout : layouts) {
                int added = addedColumns(layout, item.columns());
                if (added >= 0 && layout.types.size() + added <= maxColumns && added < bestAdded) {
                    best = layout;
                    bestAdded = added;
                }
            }
            if (best == null) {
                best = new Layout();
                layouts.add(best);
            }
            best.types.putAll(item.columns());
            best.items.add(item);
        }
        int widest = 0;
        for (int number = 0; number < layouts.size(); number++) {
            Layout layout = layouts.get(number);
            GroupPlan.Shape shape = new GroupPlan.Shape(number, List.copyOf(layout.types.keySet()), Map.copyOf(layout.types));
            for (Item item : layout.items) {
                if (item.variantKey() == null) {
                    item.plan().usePlainShape(shape);
                } else {
                    item.plan().useVariantShape(item.variantKey(), shape);
                }
            }
            widest = Math.max(widest, shape.keys().size());
        }
        return new Result(plans.size(), items.size(), groupsShared, layouts.size(), widest);
    }

    /** How many columns the item would add to the layout, or -1 when a property has another type there. */
    private static int addedColumns(Layout layout, Map<String, String> columns) {
        int added = 0;
        for (Map.Entry<String, String> column : columns.entrySet()) {
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
