package com.etolv.neo4jtool.engine.build;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The new id of every node that cannot keep its own: old id to new id. It is small (one pair per node whose id the target
 * already uses), so it lives in memory. A lookup of an id that was not renumbered answers with the id itself, and a
 * range check in front makes the usual case (an id far from every renumbered one) a pair of comparisons.
 */
public final class IdRemap {

    private static final IdRemap NONE = new IdRemap(new long[0], new long[0]);

    private final long[] olds;
    private final long[] news;
    private final Long2LongOpenHashMap map;
    private final long min;
    private final long max;

    private IdRemap(long[] olds, long[] news) {
        this.olds = olds;
        this.news = news;
        this.map = new Long2LongOpenHashMap(olds.length * 2 + 1);
        map.defaultReturnValue(-1);
        for (int i = 0; i < olds.length; i++) {
            map.put(olds[i], news[i]);
        }
        this.min = olds.length == 0 ? Long.MAX_VALUE : olds[0];
        this.max = olds.length == 0 ? Long.MIN_VALUE : olds[olds.length - 1];
    }

    public static IdRemap none() {
        return NONE;
    }

    /**
     * @param olds the ids to renumber, ascending
     * @param news their new ids, in the same order
     */
    public static IdRemap of(long[] olds, long[] news) {
        if (olds.length != news.length) {
            throw new IllegalArgumentException("Every old id needs a new one");
        }
        if (olds.length == 0) {
            return NONE;
        }
        for (int i = 1; i < olds.length; i++) {
            if (olds[i] <= olds[i - 1]) {
                throw new IllegalArgumentException("The old ids must be ascending and different");
            }
        }
        return new IdRemap(olds.clone(), news.clone());
    }

    /** The ids of {@code clash} get {@code firstNewId}, {@code firstNewId + 1}, ... in ascending order of the old id. */
    public static IdRemap above(IdSet clash, long firstNewId) {
        long[] olds = clash.toArray();
        long[] news = new long[olds.length];
        for (int i = 0; i < olds.length; i++) {
            news[i] = firstNewId + i;
        }
        return of(olds, news);
    }

    /** The ids of {@code clash} get the smallest ids that {@code used} does not hold, in ascending order of the old id. */
    public static IdRemap inGaps(IdSet clash, IdSet used) {
        long[] olds = clash.toArray();
        long[] news = new long[olds.length];
        long candidate = 0;
        for (int i = 0; i < olds.length; i++) {
            while (used.contains(candidate)) {
                candidate++;
            }
            news[i] = candidate++;
        }
        return of(olds, news);
    }

    public boolean isEmpty() {
        return olds.length == 0;
    }

    public int size() {
        return olds.length;
    }

    /** Whether this old id was renumbered. */
    public boolean contains(long id) {
        return id >= min && id <= max && map.containsKey(id);
    }

    /** The new id, or {@code id} itself when it was not renumbered. */
    public long map(long id) {
        if (id < min || id > max) {
            return id;
        }
        long mapped = map.get(id);
        return mapped < 0 ? id : mapped;
    }

    /** The old ids, ascending. */
    public long[] oldIds() {
        return olds.clone();
    }

    /** The new ids, in the order of {@link #oldIds}. */
    public long[] newIds() {
        return news.clone();
    }

    public IdSet newIdSet() {
        return IdSet.of(news);
    }

    public long highestNewId() {
        long highest = -1;
        for (long id : news) {
            highest = Math.max(highest, id);
        }
        return highest;
    }

    // ---- handing it to the fast reader process ----

    /** Writes the pairs as little-endian longs: old, new, old, new, ... */
    public void write(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(16, Math.min(olds.length, 65_536) * 16)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < olds.length; i++) {
                buffer.putLong(olds[i]).putLong(news[i]);
                if (buffer.remaining() < 16) {
                    flush(channel, buffer);
                }
            }
            flush(channel, buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static IdRemap read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int pairs = bytes.length / 16;
            long[] olds = new long[pairs];
            long[] news = new long[pairs];
            for (int i = 0; i < pairs; i++) {
                olds[i] = buffer.getLong();
                news[i] = buffer.getLong();
            }
            return of(olds, news);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void flush(FileChannel channel, ByteBuffer buffer) throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    @Override
    public String toString() {
        return olds.length + " renumbered ids" + (olds.length == 0 ? "" : ", " + olds[0] + ".." + olds[olds.length - 1]);
    }
}
