package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The property types seen for one group of entities (all nodes with the same labels, or all relationships of one type).
 * A property name that was seen with one type becomes one typed CSV column. A name seen with several types
 * ("age" as a number on some nodes and as text on others) is a conflict: the CSV import gives a column one type only,
 * so those nodes go to separate files, one file per combination of types (a "variant").
 *
 * <p>When layouts are shared (see {@link LayoutMerger}) the group is also told which {@link Shape} each of its variants is
 * written to: a layout that other groups use too, whose columns are a superset of the group's own.
 */
final class GroupPlan {

    /** More different combinations of conflicting types than this in one group: its rows keep one layout per combination. */
    static final int MAX_VARIANTS = 20_000;

    /** A layout shared by several groups: its number, its columns in order, and their types. */
    record Shape(int id, List<String> keys, Map<String, String> types) {
    }

    /** One combination of types of the conflicting properties of a group, and how many rows have it. */
    record Variant(Map<String, String> types, long rows) {
    }

    private final TreeMap<String, TreeSet<String>> typesByKey = new TreeMap<>();
    private List<String> plain;
    private List<String> conflicts;
    private long rows;
    private final TreeMap<String, Variant> variants = new TreeMap<>();
    private boolean variantsOverflowed;
    private boolean variantsKnown;
    private Shape plainShape;
    private final Map<String, Shape> variantShapes = new HashMap<>();

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

    /** The names of this group that have exactly one type, with that type, in a fixed order. */
    Map<String, String> ownPlainTypes() {
        Map<String, String> own = new TreeMap<>();
        typesByKey.forEach((key, types) -> {
            if (types.size() == 1) {
                own.put(key, types.first());
            }
        });
        return own;
    }

    // ---- the combinations of conflicting types that occur ----

    /** The name of a combination: the conflicting properties a row has, with their types, in the order of the names. */
    static String variantKey(Map<String, String> typeByConflictingKey) {
        StringBuilder key = new StringBuilder();
        new TreeMap<>(typeByConflictingKey).forEach((name, type) -> key.append(name).append('=').append(type).append('\u0001'));
        return key.toString();
    }

    /** Remembers that {@code rows} rows have this combination of types (one more pass over the data tells). */
    void addVariant(Map<String, String> types, long rows) {
        if (variantsOverflowed) {
            return;
        }
        variants.merge(variantKey(types), new Variant(new TreeMap<>(types), rows),
                (a, b) -> new Variant(a.types(), a.rows() + b.rows()));
        if (variants.size() > MAX_VARIANTS) {
            variants.clear();
            variantsOverflowed = true;
        }
    }

    /** The scan found more combinations than are kept. */
    void overflowVariants() {
        variants.clear();
        variantsOverflowed = true;
    }

    /** The scan of the combinations is over: they are all known, or too many to keep. */
    void variantsDone() {
        variantsKnown = true;
    }

    /** True when every combination of types a row can have is known, so that each can be given a shared layout. */
    boolean variantsComplete() {
        return variantsKnown && !variantsOverflowed;
    }

    Map<String, Variant> variants() {
        return variants;
    }

    // ---- where the rows of this group are written, when layouts are shared ----

    void usePlainShape(Shape shape) {
        if (!conflictKeys().isEmpty()) {
            throw new IllegalStateException("A group with conflicting property types has a layout per combination of types");
        }
        plainShape = shape;
    }

    void useVariantShape(String variantKey, Shape shape) {
        variantShapes.put(variantKey, shape);
    }

    /**
     * The shared layout a row goes to, or null when this group keeps layouts of its own. A group whose combinations of
     * types are all known must not meet one that is not: the data changed since they were read.
     */
    Shape sharedShape(Map<String, String> typeByConflictingKey) {
        if (conflictKeys().isEmpty()) {
            return plainShape;
        }
        if (variantShapes.isEmpty()) {
            return null;
        }
        Shape shape = variantShapes.get(variantKey(typeByConflictingKey));
        if (shape == null) {
            throw new BuildException("The source changed while it was being exported: a combination of property types appeared "
                    + "that was not there when it was read (" + variantKey(typeByConflictingKey).replace('\u0001', ' ').trim()
                    + "). Run the split again.");
        }
        return shape;
    }
}
