package com.etolv.neo4jtool.engine.dryrun;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/** Gives every distinct value a small int index. Used for label sets and org values, of which there are few. */
final class Interner<T> {

    private final Object2IntOpenHashMap<T> index = new Object2IntOpenHashMap<>();
    private final List<T> values = new ArrayList<>();

    Interner() {
        index.defaultReturnValue(-1);
    }

    int intern(T value) {
        int i = index.getInt(value);
        if (i < 0) {
            i = values.size();
            values.add(value);
            index.put(value, i);
        }
        return i;
    }

    /** @return the index, or -1 if the value was never interned */
    int find(T value) {
        return index.getInt(value);
    }

    T get(int i) {
        return values.get(i);
    }

    int size() {
        return values.size();
    }
}
