package com.etolv.neo4jtool.engine.split;

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

    long[] slice(int from, int to) {
        return Arrays.copyOfRange(sorted, from, to);
    }
}
