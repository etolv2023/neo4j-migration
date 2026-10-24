package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * Replaces, in the properties of nodes, a value that is exactly the old id of a renumbered node with its new id. Other
 * nodes keep the id of a node they point to in a property (a customer id on a booking, say); when that node gets a new id
 * the property must follow.
 *
 * <p>A value counts only when it <em>is</em> the id: an integer equal to it, or a text that is its plain decimal digits
 * (no sign, no spaces, no leading zero). {@code 122} and {@code "012"} are not {@code 12}. The kind stays the same: an
 * integer is replaced by an integer, a text by a text, and in a list each matching element on its own. Every value is
 * looked up in the old ids once, so an old id that is also the new id of another node is not replaced twice.
 *
 * <p>The property that holds the organisation is never changed: what the rewrite applies to depends on it.
 */
public final class ReferenceRewrite {

    /** Told about every value that was replaced. */
    public interface Listener {
        void changed(String property, Value before, Value after);
    }

    private final IdRemap remap;
    private final String orgProperty;
    private final Set<Object> orgValues;
    private final IdSet eligible;
    private final boolean lists;
    private final Set<String> excluded;

    private ReferenceRewrite(IdRemap remap, String orgProperty, Set<Object> orgValues, IdSet eligible, boolean lists,
                             Set<String> excluded) {
        this.remap = remap;
        this.orgProperty = orgProperty;
        this.orgValues = orgValues;
        this.eligible = eligible;
        this.lists = lists;
        this.excluded = excluded;
    }

    /** Applies to every node it is given: the nodes that are being moved. */
    public static ReferenceRewrite forMovedNodes(IdRemap remap, String orgProperty, boolean lists, Set<String> excluded) {
        return new ReferenceRewrite(remap, orgProperty, null, null, lists, Set.copyOf(excluded));
    }

    /**
     * Applies to the nodes of the given organisations that are in {@code eligible} (every node when it is null). With no
     * organisation it applies to no node.
     */
    public static ReferenceRewrite forOrganisations(IdRemap remap, String orgProperty, List<Object> orgIds, IdSet eligible,
                                                    boolean lists, Set<String> excluded) {
        Set<Object> values = new java.util.HashSet<>();
        orgIds.forEach(id -> values.add(normalize(id)));
        return new ReferenceRewrite(remap, orgProperty, values, eligible, lists, Set.copyOf(excluded));
    }

    public IdRemap remap() {
        return remap;
    }

    public String orgProperty() {
        return orgProperty;
    }

    /** The organisations this applies to, or null when it applies to every node it is given. */
    public Set<Object> organisations() {
        return orgValues;
    }

    public IdSet eligible() {
        return eligible;
    }

    public boolean lists() {
        return lists;
    }

    public Set<String> excluded() {
        return excluded;
    }

    /** Whether the properties of this node are looked at. */
    public boolean appliesTo(long id, Map<String, Value> props) {
        if (remap.isEmpty()) {
            return false;
        }
        if (eligible != null && !eligible.contains(id)) {
            return false;
        }
        if (orgValues == null) {
            return true;
        }
        Value org = props.get(orgProperty);
        return org != null && !org.isNull() && orgValues.contains(normalize(org.asObject()));
    }

    /**
     * @return the properties with every matching value replaced, or {@code props} itself when none matched
     */
    public Map<String, Value> apply(List<String> labels, Map<String, Value> props, Listener listener) {
        Map<String, Value> result = null;
        for (Map.Entry<String, Value> property : props.entrySet()) {
            String key = property.getKey();
            if (key.equals(orgProperty) || isExcluded(labels, key)) {
                continue;
            }
            Value replaced = replace(property.getValue());
            if (replaced != null) {
                if (result == null) {
                    result = new LinkedHashMap<>(props);
                }
                result.put(key, replaced);
                if (listener != null) {
                    listener.changed(key, property.getValue(), replaced);
                }
            }
        }
        return result == null ? props : result;
    }

    /** @return the replacement, or null when the value is not an old id */
    private Value replace(Value value) {
        String type = value.type().name();
        if (type.equals("INTEGER")) {
            long id = value.asLong();
            return remap.contains(id) ? Values.value(remap.map(id)) : null;
        }
        if (type.equals("STRING")) {
            long id = plainId(value.asString());
            return id >= 0 && remap.contains(id) ? Values.value(Long.toString(remap.map(id))) : null;
        }
        if (lists && type.startsWith("LIST")) {
            List<Value> items = new ArrayList<>();
            boolean changed = false;
            for (Value item : value.values()) {
                Value replaced = replace(item);
                changed |= replaced != null;
                items.add(replaced != null ? replaced : item);
            }
            return changed ? Values.value(items.toArray(new Value[0])) : null;
        }
        return null;
    }

    /** The id a text spells out in plain digits, or -1 when it is anything else (so "012", " 12" and "12.0" are not ids). */
    static long plainId(String text) {
        int length = text.length();
        if (length == 0 || length > 18 || (length > 1 && text.charAt(0) == '0')) {
            return -1;
        }
        long id = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            id = id * 10 + (c - '0');
        }
        return id;
    }

    private boolean isExcluded(List<String> labels, String key) {
        if (excluded.isEmpty()) {
            return false;
        }
        if (excluded.contains(key)) {
            return true;
        }
        for (String label : labels) {
            if (excluded.contains(label + "." + key)) {
                return true;
            }
        }
        return false;
    }

    /** Integers compare as longs and floats as doubles, as Cypher's {@code IN} tells them apart from texts. */
    static Object normalize(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        return value;
    }
}
