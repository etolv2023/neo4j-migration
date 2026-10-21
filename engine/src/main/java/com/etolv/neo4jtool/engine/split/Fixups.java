package com.etolv.neo4jtool.engine.split;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node properties that the CSV import cannot carry exactly. Only (node id, property name) is kept; the value is read
 * from the source again when the second pass writes it.
 */
final class Fixups {

    private final LongArrayList ids = new LongArrayList();
    private final IntArrayList keyIndex = new IntArrayList();
    private final List<String> keys = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();

    void add(long nodeId, String key) {
        ids.add(nodeId);
        keyIndex.add(index.computeIfAbsent(key, k -> {
            keys.add(k);
            return keys.size() - 1;
        }));
    }

    int size() {
        return ids.size();
    }

    long id(int i) {
        return ids.getLong(i);
    }

    String key(int i) {
        return keys.get(keyIndex.getInt(i));
    }
}
