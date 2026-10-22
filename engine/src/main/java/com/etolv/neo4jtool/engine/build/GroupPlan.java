package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The property types seen for one group of entities (all nodes with the same labels, or all relationships of one type).
 * A property name that was seen with one type becomes one typed CSV column. A name seen with several types
 * ("age" as a number on some nodes and as text on others) is a conflict: the CSV import gives a column one type only,
 * so those nodes go to separate files, one file per combination of types.
 */
final class GroupPlan {

    private final TreeMap<String, TreeSet<String>> typesByKey = new TreeMap<>();
    private List<String> plain;
    private List<String> conflicts;
    private long rows;
    /** Set by {@link LayoutMerger}: the columns of the shared layout this group is written to, and their types. */
    private List<String> layoutKeys;
    private Map<String, String> layoutTypes;

    void observe(String key, String type) {
        typesByKey.computeIfAbsent(key, k -> new TreeSet<>()).add(type);
        plain = null;
        conflicts = null;
    }

    /** One more entity of this group was seen. */
    void observeRow() {
        rows++;
    }

    long rows() {
        return rows;
    }

    void merge(GroupPlan other) {
        rows += other.rows;
        other.typesByKey.forEach((key, types) -> typesByKey.computeIfAbsent(key, k -> new TreeSet<>()).addAll(types));
        plain = null;
        conflicts = null;
    }

    /** Computes what is cached, so that readers on several threads only read. */
    void freeze() {
        plainKeys();
        conflictKeys();
    }

    /** The names of this group that have exactly one type, with that type, in a fixed order (before any merging of layouts). */
    Map<String, String> ownPlainTypes() {
        Map<String, String> own = new TreeMap<>();
        typesByKey.forEach((key, types) -> {
            if (types.size() == 1) {
                own.put(key, types.first());
            }
        });
        return own;
    }

    /**
     * Writes this group to a layout shared with other groups: the columns are the keys of {@code keys}, with their
     * {@code types}, a superset of the group's own columns. Only for a group without conflicting types.
     */
    void useLayout(List<String> keys, Map<String, String> types) {
        if (!conflictKeys().isEmpty()) {
            throw new IllegalStateException("A group with conflicting property types cannot share a layout");
        }
        layoutKeys = keys;
        layoutTypes = types;
    }

    /** Names that have exactly one type, in a fixed order; the columns of the shared layout when there is one. */
    List<String> plainKeys() {
        if (layoutKeys != null) {
            return layoutKeys;
        }
        if (plain == null) {
            List<String> keys = new ArrayList<>();
            typesByKey.forEach((key, types) -> {
                if (types.size() == 1) {
                    keys.add(key);
                }
            });
            plain = keys;
        }
        return plain;
    }

    List<String> conflictKeys() {
        if (conflicts == null) {
            List<String> keys = new ArrayList<>();
            typesByKey.forEach((key, types) -> {
                if (types.size() > 1) {
                    keys.add(key);
                }
            });
            conflicts = keys;
        }
        return conflicts;
    }

    String plainType(String key) {
        if (layoutTypes != null) {
            return layoutTypes.get(key);
        }
        return typesByKey.get(key).first();
    }

    boolean knows(String key, String type) {
        TreeSet<String> types = typesByKey.get(key);
        return types != null && types.contains(type);
    }
}
