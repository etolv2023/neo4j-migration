package com.etolv.neo4jtool.engine.build;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;

/** The ids of the selected nodes, sorted, so that "is this node selected?" is a binary search. */
public final class IdSet {

    private final long[] sorted;

    private IdSet(long[] sorted) {
        this.sorted = sorted;
    }

    public static IdSet of(LongArrayList ids) {
        long[] array = ids.toLongArray();
        Arrays.sort(array);
        return new IdSet(array);
    }

    public static IdSet of(long... ids) {
        long[] array = ids.clone();
        Arrays.sort(array);
        return new IdSet(array);
    }

    /** All ids of both sets (they may overlap). */
    public static IdSet union(IdSet a, IdSet b) {
        long[] merged = new long[a.sorted.length + b.sorted.length];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < a.sorted.length || j < b.sorted.length) {
            long next;
            if (j >= b.sorted.length || (i < a.sorted.length && a.sorted[i] <= b.sorted[j])) {
                next = a.sorted[i++];
            } else {
                next = b.sorted[j++];
            }
            if (n == 0 || merged[n - 1] != next) {
                merged[n++] = next;
            }
        }
        return new IdSet(Arrays.copyOf(merged, n));
    }

    /** The ids of this set that are not in {@code other}. */
    public IdSet minus(IdSet other) {
        return filter(other, false);
    }

    /** The ids of this set that are also in {@code other}. */
    public IdSet intersect(IdSet other) {
        return filter(other, true);
    }

    private IdSet filter(IdSet other, boolean keepCommon) {
        long[] out = new long[sorted.length];
        int n = 0;
        for (long id : sorted) {
            if (other.contains(id) == keepCommon) {
                out[n++] = id;
            }
        }
        return new IdSet(Arrays.copyOf(out, n));
    }

    public int size() {
        return sorted.length;
    }

    public boolean contains(long id) {
        return Arrays.binarySearch(sorted, id) >= 0;
    }

    /** The largest id, or -1 when the set is empty. */
    public long max() {
        return sorted.length == 0 ? -1 : sorted[sorted.length - 1];
    }

    public long[] toArray() {
        return sorted.clone();
    }

    long[] slice(int from, int to) {
        return Arrays.copyOfRange(sorted, from, to);
    }
}
