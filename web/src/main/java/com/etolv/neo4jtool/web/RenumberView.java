package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.build.RenumberPlanner;
import com.etolv.neo4jtool.engine.build.ReferenceScan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** What the dry-run report shows about the properties that hold an old id, ready for the page. */
final class RenumberView {

    /**
     * One property name over all the nodes that have it.
     *
     * @param moved    properties in the nodes that are restored
     * @param existing properties in company nodes already on the target
     * @param newer    properties in company nodes made after the backup
     */
    record Property(String name, long moved, long existing, long newer, boolean excluded) {

        long total() {
            return moved + existing + newer;
        }
    }

    private RenumberView() {
    }

    static List<Property> properties(RenumberPlanner.Plan plan, Set<String> excluded) {
        if (plan == null || plan.references() == null) {
            return List.of();
        }
        Map<String, long[]> byName = new TreeMap<>();
        add(byName, plan.references().moved(), 0);
        add(byName, plan.references().existing(), 1);
        add(byName, plan.references().newer(), 2);
        List<Property> rows = new ArrayList<>();
        byName.forEach((name, c) -> rows.add(new Property(name, c[0], c[1], c[2], excluded.contains(name))));
        rows.sort(Comparator.comparingLong(Property::total).reversed().thenComparing(Property::name));
        return rows;
    }

    private static void add(Map<String, long[]> byName, ReferenceScan.Result result, int column) {
        for (ReferenceScan.Group group : result.groups()) {
            byName.computeIfAbsent(group.property(), k -> new long[3])[column] += group.count();
        }
    }

    /** The id table as CSV: old id, new id, what the id is in the source, what holds it on the target. */
    static String mappingCsv(RenumberPlanner.Plan plan) {
        StringBuilder csv = new StringBuilder("old_id,new_id,labels,org,target_labels,target_org\n");
        for (RenumberPlanner.Entry e : plan.mapping()) {
            csv.append(e.oldId()).append(',').append(e.newId()).append(',').append(cell(String.join(";", e.labels()))).append(',')
                    .append(cell(e.org())).append(',').append(cell(String.join(";", e.targetLabels()))).append(',')
                    .append(cell(e.targetOrg())).append('\n');
        }
        return csv.toString();
    }

    private static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        return text.contains(",") || text.contains("\"") || text.contains("\n") ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
