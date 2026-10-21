package com.etolv.neo4jtool.engine.dryrun;

import it.unimi.dsi.fastutil.Arrays;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;

/**
 * The selected source nodes held compactly: two longs and one byte per node.
 * Ids are kept sorted so a node can be found by binary search.
 */
final class SelectedNodes {

    static final byte UNSET = 0;

    private final Interner<List<String>> labelSets = new Interner<>();
    private final Interner<Object> orgs = new Interner<>();
    private LongArrayList ids = new LongArrayList();
    private LongArrayList packed = new LongArrayList();
    private long[] sortedIds;
    private long[] sortedPacked;
    private byte[] status;
    private boolean ascending = true;
    private long lastId = -1;

    /** @param sortedLabels labels in alphabetical order, so equal label sets are equal lists */
    void add(long id, List<String> sortedLabels, Object org) {
        if (sortedIds != null) {
            throw new IllegalStateException("Already sealed");
        }
        if (id <= lastId) {
            ascending = false;
        }
        lastId = id;
        int labelSet = labelSets.intern(sortedLabels);
        int orgIndex = orgs.intern(org);
        ids.add(id);
        packed.add(((long) labelSet << 32) | (orgIndex & 0xFFFFFFFFL));
    }

    /** Call once after the last add: sorts by id if needed and allocates the status array. */
    void seal() {
        sortedIds = ids.toLongArray();
        sortedPacked = packed.toLongArray();
        ids = null;
        packed = null;
        if (!ascending) {
            sortByIdKeepingPacked(sortedIds, sortedPacked);
        }
        status = new byte[sortedIds.length];
    }

    private static void sortByIdKeepingPacked(long[] idArray, long[] packedArray) {
        Arrays.quickSort(0, idArray.length,
                (a, b) -> Long.compare(idArray[a], idArray[b]),
                (a, b) -> {
                    long id = idArray[a];
                    idArray[a] = idArray[b];
                    idArray[b] = id;
                    long p = packedArray[a];
                    packedArray[a] = packedArray[b];
                    packedArray[b] = p;
                });
    }

    int size() {
        return sortedIds.length;
    }

    long id(int i) {
        return sortedIds[i];
    }

    int labelSetIdx(int i) {
        return (int) (sortedPacked[i] >>> 32);
    }

    int orgIdx(int i) {
        return (int) sortedPacked[i];
    }

    List<String> labelsOf(int i) {
        return labelSets.get(labelSetIdx(i));
    }

    Object orgOf(int i) {
        return orgs.get(orgIdx(i));
    }

    List<String> labelSet(int labelSetIndex) {
        return labelSets.get(labelSetIndex);
    }

    Object org(int orgIndex) {
        return orgs.get(orgIndex);
    }

    int labelSetCount() {
        return labelSets.size();
    }

    int orgCount() {
        return orgs.size();
    }

    /** @return the index of this label set among the selected nodes' label sets, or -1 if none has it */
    int findLabelSet(List<String> sortedLabels) {
        return labelSets.find(sortedLabels);
    }

    int findOrg(Object org) {
        return orgs.find(org);
    }

    long[] idsSlice(int from, int to) {
        return java.util.Arrays.copyOfRange(sortedIds, from, to);
    }

    /** @return position of the id among the selected nodes, or a negative number if it is not selected */
    int indexOf(long id) {
        return java.util.Arrays.binarySearch(sortedIds, id);
    }

    byte status(int i) {
        return status[i];
    }

    void setStatus(int i, byte value) {
        status[i] = value;
    }

    long[] countsByLabelSet() {
        long[] counts = new long[labelSets.size()];
        for (long p : sortedPacked) {
            counts[(int) (p >>> 32)]++;
        }
        return counts;
    }

    long[] countsByOrg() {
        long[] counts = new long[orgs.size()];
        for (long p : sortedPacked) {
            counts[(int) p]++;
        }
        return counts;
    }
}
