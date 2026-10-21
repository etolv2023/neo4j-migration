package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.unimi.dsi.fastutil.longs.LongIterator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdSetTest {

    @TempDir
    Path temp;

    @AfterEach
    void restoreBudget() {
        IdSet.configure(512L * 1024 * 1024, null);
    }

    private static TreeSet<Long> random(long seed, int count, long range) {
        Random random = new Random(seed);
        TreeSet<Long> ids = new TreeSet<>();
        while (ids.size() < count) {
            ids.add((long) (random.nextDouble() * range));
        }
        return ids;
    }

    private static IdSet build(TreeSet<Long> ids) {
        IdSet.Builder builder = new IdSet.Builder();
        ids.forEach(builder::add);
        return builder.build();
    }

    private static void assertSame(IdSet set, TreeSet<Long> expected) {
        assertThat(set.size()).isEqualTo(expected.size());
        assertThat(set.max()).isEqualTo(expected.isEmpty() ? -1 : expected.last());
        LongIterator it = set.iterator();
        for (long id : expected) {
            assertThat(it.nextLong()).isEqualTo(id);
        }
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    void smallBitmapAndSpilledSetsAnswerTheSame() {
        TreeSet<Long> ids = random(1, 20_000, 10_000_000_000L);
        IdSet bitmap = build(ids);
        assertThat(bitmap.spilled()).isFalse();
        IdSet.configure(1024, temp);
        IdSet spilled = build(ids);
        assertThat(spilled.spilled()).isTrue();
        IdSet small = build(random(2, 100, 1000));
        assertThat(small.spilled()).isFalse();

        for (IdSet set : new IdSet[] {bitmap, spilled}) {
            assertSame(set, ids);
            Random probe = new Random(3);
            for (int i = 0; i < 2000; i++) {
                long id = (long) (probe.nextDouble() * 10_000_000_000L);
                assertThat(set.contains(id)).isEqualTo(ids.contains(id));
            }
            for (long id : ids.stream().limit(50).toList()) {
                assertThat(set.contains(id)).isTrue();
            }
        }
        long[] all = ids.stream().mapToLong(Long::longValue).toArray();
        for (IdSet set : new IdSet[] {bitmap, spilled, small}) {
            long n = set.size();
            long[] slice = set.slice(n / 3, n / 3 + 10);
            long[] expected = set == small ? set.toArray() : all;
            long offset = set == small ? 0 : 0;
            for (int i = 0; i < slice.length; i++) {
                assertThat(slice[i]).isEqualTo(expected[(int) (n / 3 + i + offset)]);
            }
            LongIterator from = set.iteratorFrom(n - 2);
            assertThat(from.nextLong()).isEqualTo(set.slice(n - 2, n)[0]);
            assertThat(set.iteratorFrom(n).hasNext()).isFalse();
        }
    }

    @Test
    void setOperationsMatchTreeSets() {
        TreeSet<Long> a = random(4, 5000, 100_000);
        TreeSet<Long> b = random(5, 5000, 100_000);
        for (boolean spill : new boolean[] {false, true}) {
            IdSet.configure(spill ? 1024 : 512L * 1024 * 1024, temp);
            IdSet x = build(a);
            IdSet y = build(b);
            TreeSet<Long> union = new TreeSet<>(a);
            union.addAll(b);
            TreeSet<Long> common = new TreeSet<>(a);
            common.retainAll(b);
            TreeSet<Long> only = new TreeSet<>(a);
            only.removeAll(b);
            assertSame(IdSet.union(x, y), union);
            assertSame(x.intersect(y), common);
            assertSame(x.minus(y), only);
        }
    }

    @Test
    void ofSortsAndRemovesDuplicatesAndEmptyWorks() {
        IdSet set = IdSet.of(9, 3, 3, 7, 1_000_000_000_000L);
        assertThat(set.toArray()).containsExactly(3, 7, 9, 1_000_000_000_000L);
        assertThat(set.size()).isEqualTo(4);
        IdSet empty = IdSet.empty();
        assertThat(empty.size()).isZero();
        assertThat(empty.max()).isEqualTo(-1);
        assertThat(empty.contains(0)).isFalse();
        assertThat(empty.iterator().hasNext()).isFalse();
    }

    @Test
    void idsMustArriveInOrder() {
        IdSet.Builder builder = new IdSet.Builder().add(5);
        assertThatThrownBy(() -> builder.add(4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeAndReadRoundTrip() throws Exception {
        TreeSet<Long> ids = random(6, 30_000, 5_000_000_000L);
        Path file = temp.resolve("ids.bin");
        build(ids).write(file);
        assertThat(Files.size(file)).isEqualTo(ids.size() * 8L);
        assertSame(IdSet.read(file), ids);
        IdSet.configure(1024, temp);
        IdSet spilled = IdSet.read(file);
        assertThat(spilled.spilled()).isTrue();
        assertSame(spilled, ids);
    }
}
