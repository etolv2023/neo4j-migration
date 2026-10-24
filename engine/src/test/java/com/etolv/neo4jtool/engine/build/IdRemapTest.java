package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdRemapTest {

    @Test
    void anIdThatWasNotRenumberedMapsToItself() {
        IdRemap remap = IdRemap.of(new long[] {10, 20}, new long[] {100, 101});

        assertThat(remap.map(10)).isEqualTo(100);
        assertThat(remap.map(20)).isEqualTo(101);
        assertThat(remap.map(15)).isEqualTo(15);
        assertThat(remap.map(5)).isEqualTo(5);
        assertThat(remap.map(21)).isEqualTo(21);
        assertThat(remap.contains(10)).isTrue();
        assertThat(remap.contains(15)).isFalse();
        assertThat(remap.contains(100)).as("a new id is not an old one").isFalse();
    }

    @Test
    void noneIsEmptyAndChangesNothing() {
        assertThat(IdRemap.none().isEmpty()).isTrue();
        assertThat(IdRemap.none().map(7)).isEqualTo(7);
        assertThat(IdRemap.none().contains(7)).isFalse();
        assertThat(IdRemap.of(new long[0], new long[0])).isSameAs(IdRemap.none());
    }

    @Test
    void aboveGivesConsecutiveIdsInTheOrderOfTheOldOnes() {
        IdRemap remap = IdRemap.above(IdSet.of(3, 9, 40), 1000);

        assertThat(remap.oldIds()).containsExactly(3, 9, 40);
        assertThat(remap.newIds()).containsExactly(1000, 1001, 1002);
        assertThat(remap.highestNewId()).isEqualTo(1002);
        assertThat(remap.newIdSet().contains(1001)).isTrue();
    }

    @Test
    void inGapsUsesTheSmallestFreeIds() {
        IdRemap remap = IdRemap.inGaps(IdSet.of(3, 9, 40), IdSet.of(0, 1, 3, 4, 5, 9));

        assertThat(remap.newIds()).containsExactly(2, 6, 7);
    }

    @Test
    void oldIdsMustBeAscendingAndDifferent() {
        assertThatThrownBy(() -> IdRemap.of(new long[] {5, 5}, new long[] {1, 2})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdRemap.of(new long[] {6, 5}, new long[] {1, 2})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdRemap.of(new long[] {5}, new long[] {1, 2})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itSurvivesTheFileTheReaderProcessGetsIt(@TempDir Path dir) {
        long[] olds = new long[100_000];
        long[] news = new long[olds.length];
        for (int i = 0; i < olds.length; i++) {
            olds[i] = 3L * i + 1;
            news[i] = 9_000_000_000L + i;
        }
        Path file = dir.resolve("remap.pairs");
        IdRemap.of(olds, news).write(file);

        IdRemap read = IdRemap.read(file);

        assertThat(read.size()).isEqualTo(olds.length);
        assertThat(read.map(1)).isEqualTo(9_000_000_000L);
        assertThat(read.map(3L * 99_999 + 1)).isEqualTo(9_000_099_999L);
        assertThat(read.map(2)).isEqualTo(2);
        assertThat(Files.exists(file)).isTrue();
    }
}
