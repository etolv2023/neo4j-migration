package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.List;
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

    void observe(String key, String type) {
        typesByKey.computeIfAbsent(key, k -> new TreeSet<>()).add(type);
        plain = null;
        conflicts = null;
    }

    void merge(GroupPlan other) {
        other.typesByKey.forEach((key, types) -> typesByKey.computeIfAbsent(key, k -> new TreeSet<>()).addAll(types));
        plain = null;
        conflicts = null;
    }

    /** Computes what is cached, so that readers on several threads only read. */
    void freeze() {
        plainKeys();
        conflictKeys();
    }

    /** Names that have exactly one type, in a fixed order. */
    List<String> plainKeys() {
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
        return typesByKey.get(key).first();
    }

    boolean knows(String key, String type) {
        TreeSet<String> types = typesByKey.get(key);
        return types != null && types.contains(type);
    }
}
