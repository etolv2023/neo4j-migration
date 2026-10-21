package com.etolv.neo4jtool.engine.build;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.NoSuchElementException;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * The ids of a set of nodes, kept in ascending order. Three representations, chosen by size:
 * a plain sorted array (small sets), a compressed bitmap (about 2 bytes per id when the ids are spread out, far less
 * when they are dense) and, once the bitmap would pass the memory budget, a file of sorted ids that is memory-mapped, so
 * the operating system decides what stays in memory. All of them answer the same questions the same way.
 *
 * <p>A set is read-only. Sets are built with {@link Builder} (ids in ascending order, which every source here has) or
 * with {@link #of}.
 */
public abstract class IdSet {

    /** Largest in-heap bitmap, in bytes, before a set is written to a file instead. */
    private static volatile long memoryBudget = 512L * 1024 * 1024;
    private static volatile Path spillDirectory = Path.of(System.getProperty("java.io.tmpdir"));
    private static final Cleaner CLEANER = Cleaner.create();

    /** Ids per memory-mapped chunk of a spilled set (1 GiB of longs). */
    private static final int CHUNK_BITS = 27;

    /**
     * @param bytes budget of one in-heap id set; bigger sets are kept in a memory-mapped file in {@code spillDir}
     */
    public static void configure(long bytes, Path spillDir) {
        memoryBudget = Math.max(1024, bytes);
        if (spillDir != null) {
            spillDirectory = spillDir;
        }
    }

    IdSet() {
    }

    // ---- creating ----

    public static IdSet empty() {
        return new ArrayIdSet(new long[0]);
    }

    public static IdSet of(LongArrayList ids) {
        return of(ids.toLongArray());
    }

    public static IdSet of(long... ids) {
        long[] array = ids.clone();
        Arrays.sort(array);
        Builder builder = new Builder();
        for (long id : array) {
            builder.add(id);
        }
        return builder.build();
    }

    /** Reads a file of sorted ids as {@link #write} makes it. The set lives in memory when it is small enough, otherwise it stays in the file. */
    public static IdSet read(Path file) {
        try {
            long count = Files.size(file) / Long.BYTES;
            Builder builder = new Builder();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
                long done = 0;
                while (done < count) {
                    buffer.clear();
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                        // fill the buffer
                    }
                    buffer.flip();
                    while (buffer.remaining() >= Long.BYTES) {
                        builder.add(buffer.getLong());
                        done++;
                    }
                    if (buffer.limit() == 0) {
                        break;
                    }
                }
            }
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the ids, ascending, as little-endian longs. */
    public void write(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
            LongIterator it = iterator();
            while (it.hasNext()) {
                buffer.putLong(it.nextLong());
                if (!buffer.hasRemaining()) {
                    flush(channel, buffer);
                }
            }
            flush(channel, buffer);
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

    // ---- questions ----

    public abstract long size();

    public abstract boolean contains(long id);

    /** The largest id, or -1 when the set is empty. */
    public abstract long max();

    /** All ids, ascending. */
    public abstract LongIterator iterator();

    /** The ids from position {@code from} (0 is the smallest id), ascending. */
    public abstract LongIterator iteratorFrom(long from);

    /** The ids at positions {@code from} (inclusive) to {@code to} (exclusive). */
    public long[] slice(long from, long to) {
        long[] out = new long[(int) (to - from)];
        LongIterator it = iteratorFrom(from);
        for (int i = 0; i < out.length; i++) {
            out[i] = it.nextLong();
        }
        return out;
    }

    /** Every id as an array. Only for sets known to be small. */
    public long[] toArray() {
        if (size() > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("The set has " + size() + " ids; it cannot be one array");
        }
        return slice(0, size());
    }

    /** True when the ids are kept in a file, not in memory. */
    public boolean spilled() {
        return false;
    }

    // ---- combining ----

    /** All ids of both sets (they may overlap). */
    public static IdSet union(IdSet a, IdSet b) {
        if (a instanceof RoaringIdSet x && b instanceof RoaringIdSet y) {
            Roaring64Bitmap merged = x.bitmap.clone();
            merged.or(y.bitmap);
            if (merged.getLongSizeInBytes() <= memoryBudget) {
                return new RoaringIdSet(merged);
            }
        }
        Builder out = new Builder();
        LongIterator i = a.iterator();
        LongIterator j = b.iterator();
        boolean hasI = i.hasNext();
        boolean hasJ = j.hasNext();
        long x = hasI ? i.nextLong() : 0;
        long y = hasJ ? j.nextLong() : 0;
        while (hasI || hasJ) {
            if (!hasJ || (hasI && x <= y)) {
                out.add(x);
                hasI = i.hasNext();
                if (hasI) {
                    x = i.nextLong();
                }
            } else {
                out.add(y);
                hasJ = j.hasNext();
                if (hasJ) {
                    y = j.nextLong();
                }
            }
        }
        return out.build();
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
        Builder out = new Builder();
        LongIterator it = iterator();
        while (it.hasNext()) {
            long id = it.nextLong();
            if (other.contains(id) == keepCommon) {
                out.add(id);
            }
        }
        return out.build();
    }

    // ---- building ----

    /** Collects ids in any order (a repeated id counts once) and turns them into a set. */
    public static final class Collector {

        private final Roaring64Bitmap bitmap = new Roaring64Bitmap();

        public Collector add(long id) {
            bitmap.add(id);
            return this;
        }

        public IdSet build() {
            Builder builder = new Builder();
            org.roaringbitmap.longlong.LongIterator it = bitmap.getLongIterator();
            while (it.hasNext()) {
                builder.add(it.next());
            }
            return builder.build();
        }
    }

    /** Collects ids, which must arrive in ascending order (a repeated id is ignored). Spills to a file when the set grows past the budget. */
    public static final class Builder {

        private Roaring64Bitmap bitmap = new Roaring64Bitmap();
        private long last = -1;
        private long count;
        private long nextCheck = 1024;
        private Spill spill;

        public Builder add(long id) {
            if (id < last) {
                throw new IllegalArgumentException("Ids must be added in ascending order: " + id + " after " + last);
            }
            if (id == last) {
                return this;
            }
            last = id;
            count++;
            if (spill != null) {
                spill.add(id);
                return this;
            }
            bitmap.add(id);
            if (count >= nextCheck) {
                // measuring walks every container, so measure a little more often while small and rarely when big
                nextCheck = count + Math.max(1024, count / 16);
                if (bitmap.getLongSizeInBytes() > memoryBudget) {
                    spill = new Spill();
                    org.roaringbitmap.longlong.LongIterator it = bitmap.getLongIterator();
                    while (it.hasNext()) {
                        spill.add(it.next());
                    }
                    bitmap = null;
                }
            }
            return this;
        }

        public long size() {
            return count;
        }

        public IdSet build() {
            if (spill != null) {
                return spill.finish(count, last);
            }
            if (count < 4096) {
                long[] small = new long[(int) count];
                org.roaringbitmap.longlong.LongIterator it = bitmap.getLongIterator();
                for (int i = 0; i < small.length; i++) {
                    small[i] = it.next();
                }
                return new ArrayIdSet(small);
            }
            bitmap.runOptimize();
            return new RoaringIdSet(bitmap);
        }
    }

    /** The file a growing set is written to while it is built. */
    private static final class Spill {

        private final Path file;
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);

        Spill() {
            try {
                Files.createDirectories(spillDirectory);
                file = Files.createTempFile(spillDirectory, "idset-", ".bin");
                channel = FileChannel.open(file, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void add(long id) {
            try {
                buffer.putLong(id);
                if (!buffer.hasRemaining()) {
                    flush(channel, buffer);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        IdSet finish(long count, long max) {
            try {
                flush(channel, buffer);
                channel.close();
                MappedIdSet set = new MappedIdSet(file, count, max);
                CLEANER.register(set, () -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // a temporary file; the operating system's temp cleaning gets it
                    }
                });
                return set;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ---- representations ----

    private static final class ArrayIdSet extends IdSet {

        private final long[] sorted;

        ArrayIdSet(long[] sorted) {
            this.sorted = sorted;
        }

        @Override
        public long size() {
            return sorted.length;
        }

        @Override
        public boolean contains(long id) {
            return Arrays.binarySearch(sorted, id) >= 0;
        }

        @Override
        public long max() {
            return sorted.length == 0 ? -1 : sorted[sorted.length - 1];
        }

        @Override
        public LongIterator iterator() {
            return iteratorFrom(0);
        }

        @Override
        public LongIterator iteratorFrom(long from) {
            return new LongIterator() {
                private int next = (int) from;

                @Override
                public boolean hasNext() {
                    return next < sorted.length;
                }

                @Override
                public long nextLong() {
                    if (next >= sorted.length) {
                        throw new NoSuchElementException();
                    }
                    return sorted[next++];
                }
            };
        }

        @Override
        public long[] slice(long from, long to) {
            return Arrays.copyOfRange(sorted, (int) from, (int) to);
        }
    }

    private static final class RoaringIdSet extends IdSet {

        private final Roaring64Bitmap bitmap;

        RoaringIdSet(Roaring64Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public long size() {
            return bitmap.getLongCardinality();
        }

        @Override
        public boolean contains(long id) {
            return bitmap.contains(id);
        }

        @Override
        public long max() {
            return bitmap.isEmpty() ? -1 : bitmap.last();
        }

        @Override
        public LongIterator iterator() {
            return wrap(bitmap.getLongIterator());
        }

        @Override
        public LongIterator iteratorFrom(long from) {
            if (from <= 0) {
                return iterator();
            }
            if (from >= size()) {
                return wrap(new Roaring64Bitmap().getLongIterator());
            }
            return wrap(bitmap.getLongIteratorFrom(bitmap.select(from)));
        }

        private static LongIterator wrap(org.roaringbitmap.longlong.LongIterator it) {
            return new LongIterator() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public long nextLong() {
                    return it.next();
                }
            };
        }
    }

    private static final class MappedIdSet extends IdSet {

        private final long count;
        private final long max;
        private final LongBuffer[] chunks;

        MappedIdSet(Path file, long count, long max) throws IOException {
            this.count = count;
            this.max = max;
            int n = (int) ((count + (1L << CHUNK_BITS) - 1) >> CHUNK_BITS);
            chunks = new LongBuffer[n];
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                for (int i = 0; i < n; i++) {
                    long first = (long) i << CHUNK_BITS;
                    long length = Math.min(1L << CHUNK_BITS, count - first);
                    MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, first * Long.BYTES, length * Long.BYTES);
                    chunks[i] = mapped.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
                }
            }
        }

        private long at(long index) {
            return chunks[(int) (index >> CHUNK_BITS)].get((int) (index & ((1L << CHUNK_BITS) - 1)));
        }

        @Override
        public long size() {
            return count;
        }

        @Override
        public boolean contains(long id) {
            long low = 0;
            long high = count - 1;
            while (low <= high) {
                long mid = (low + high) >>> 1;
                long value = at(mid);
                if (value < id) {
                    low = mid + 1;
                } else if (value > id) {
                    high = mid - 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long max() {
            return count == 0 ? -1 : max;
        }

        @Override
        public LongIterator iterator() {
            return iteratorFrom(0);
        }

        @Override
        public LongIterator iteratorFrom(long from) {
            return new LongIterator() {
                private long next = from;

                @Override
                public boolean hasNext() {
                    return next < count;
                }

                @Override
                public long nextLong() {
                    if (next >= count) {
                        throw new NoSuchElementException();
                    }
                    return at(next++);
                }
            };
        }

        @Override
        public boolean spilled() {
            return true;
        }
    }
}
