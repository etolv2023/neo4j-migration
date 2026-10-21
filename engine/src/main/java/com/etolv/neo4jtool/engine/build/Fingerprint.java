package com.etolv.neo4jtool.engine.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Value;

/**
 * A 64-bit hash of a node or a relationship over everything that makes it what it is: id, labels, properties with
 * their types (and, for a relationship, its ends and type). Hashes are added up, so the total does not depend on the
 * order in which entities are read. The split adds up the hashes of what it wrote, reads the new database back with the
 * same code and compares the totals.
 */
final class Fingerprint {

    private Fingerprint() {
    }

    static long node(long id, List<String> labels, Map<String, Value> properties) {
        StringBuilder text = new StringBuilder("N").append(id).append('|');
        List<String> sorted = new ArrayList<>(labels);
        Collections.sort(sorted);
        for (String label : sorted) {
            text.append(label.length()).append(':').append(label).append(',');
        }
        text.append('|');
        properties(properties, text);
        return hash(text);
    }

    /** Relationship ids are new in the copy, so a relationship is identified by its ends, type and properties. */
    static long relationship(long start, long end, String type, Map<String, Value> properties) {
        StringBuilder text = new StringBuilder("R").append(start).append('>').append(end).append('|')
                .append(type.length()).append(':').append(type).append('|');
        properties(properties, text);
        return hash(text);
    }

    private static void properties(Map<String, Value> properties, StringBuilder text) {
        List<String> keys = new ArrayList<>(properties.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            text.append(key.length()).append(':').append(key).append('=');
            ValueCodec.canonical(properties.get(key), text);
            text.append(';');
        }
    }

    private static long hash(CharSequence text) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        // Final mix, so that nearby texts give unrelated hashes and the sum does not cancel out.
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
