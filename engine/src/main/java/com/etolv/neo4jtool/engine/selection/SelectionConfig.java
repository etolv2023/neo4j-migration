package com.etolv.neo4jtool.engine.selection;

import java.util.ArrayList;
import java.util.List;

/**
 * Which nodes a run is about. Different kinds of criteria are combined with AND;
 * values inside one criterion are combined with OR. Ids and id ranges together form one criterion.
 *
 * <pre>
 * org_property: org_id        # property that identifies the organisation (default org_id)
 * org_ids: [12, 45]           # nodes of these organisations
 * labels: [Booking, Invoice]  # nodes that have at least one of these labels
 * ids: [1001, 1002]           # explicit node ids
 * id_ranges: [[5000, 9000]]   # inclusive id ranges
 * exclude_ids: [77]           # never selected
 * include_neighbours: true    # also nodes with an empty org property that are directly connected to a selected node
 * </pre>
 *
 * <p>With {@code include_neighbours} a node that has no organisation of its own (an invoice line that only belongs to an
 * invoice) comes along with the nodes it is connected to. Only the first step is followed, and a neighbour that carries
 * another organisation's value is never added. The label, id and exclusion criteria do not apply to such neighbours,
 * except that an excluded id stays excluded.
 */
public record SelectionConfig(
        String orgProperty,
        List<Object> orgIds,
        List<String> labels,
        List<Long> ids,
        List<List<Long>> idRanges,
        List<Long> excludeIds,
        boolean includeNeighbours) {

    public static final String DEFAULT_ORG_PROPERTY = "org_id";

    public SelectionConfig(String orgProperty, List<Object> orgIds, List<String> labels, List<Long> ids,
                           List<List<Long>> idRanges, List<Long> excludeIds) {
        this(orgProperty, orgIds, labels, ids, idRanges, excludeIds, false);
    }

    public SelectionConfig {
        orgProperty = (orgProperty == null || orgProperty.isBlank()) ? DEFAULT_ORG_PROPERTY : orgProperty.trim();
        orgIds = normalizeOrgIds(orgIds);
        labels = nonBlank(labels);
        ids = nonNegative(ids, "ids");
        excludeIds = nonNegative(excludeIds, "exclude_ids");
        idRanges = validRanges(idRanges);
    }

    public static SelectionConfig everything() {
        return new SelectionConfig(null, null, null, null, null, null, false);
    }

    /** The same selection with neighbours switched on or off. */
    public SelectionConfig withNeighbours(boolean include) {
        return new SelectionConfig(orgProperty, orgIds, labels, ids, idRanges, excludeIds, include);
    }

    /** True when no criterion narrows the selection, so every node would be selected. */
    public boolean selectsEverything() {
        return orgIds.isEmpty() && labels.isEmpty() && ids.isEmpty() && idRanges.isEmpty();
    }

    private static List<Object> normalizeOrgIds(List<Object> values) {
        if (values == null) {
            return List.of();
        }
        List<Object> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("org_ids must not contain null");
            }
            // YAML and JSON give Integer for small numbers while Neo4j returns Long.
            result.add(value instanceof Integer || value instanceof Short || value instanceof Byte
                    ? Long.valueOf(((Number) value).longValue()) : value);
        }
        return List.copyOf(result);
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("labels must not contain blank values");
            }
        }
        return List.copyOf(values);
    }

    private static List<Long> nonNegative(List<Long> values, String name) {
        if (values == null) {
            return List.of();
        }
        for (Long value : values) {
            if (value == null || value < 0) {
                throw new IllegalArgumentException(name + " must contain non-negative numbers only");
            }
        }
        return List.copyOf(values);
    }

    private static List<List<Long>> validRanges(List<List<Long>> ranges) {
        if (ranges == null) {
            return List.of();
        }
        List<List<Long>> result = new ArrayList<>(ranges.size());
        for (List<Long> range : ranges) {
            if (range == null || range.size() != 2 || range.get(0) == null || range.get(1) == null) {
                throw new IllegalArgumentException("Each id range must be a pair [from, to]");
            }
            if (range.get(0) < 0 || range.get(0) > range.get(1)) {
                throw new IllegalArgumentException("Invalid id range " + range + ": need 0 <= from <= to");
            }
            result.add(List.copyOf(range));
        }
        return List.copyOf(result);
    }
}
