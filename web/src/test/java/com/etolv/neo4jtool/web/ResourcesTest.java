package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResourcesTest {

    @Test
    void readsMemoryInBytesFromProcMeminfo() {
        var values = Resources.parseMeminfo(List.of("MemTotal:       16384000 kB", "MemFree:          292900 kB",
                "MemAvailable:   12000000 kB", "SwapTotal:       2097148 kB", "SwapFree:        2097148 kB", "HugePages_Total:       0",
                "garbage", "Bad: x kB"));

        assertThat(values.get("MemTotal")).isEqualTo(16384000L * 1024);
        assertThat(values.get("MemAvailable")).isEqualTo(12000000L * 1024);
        assertThat(values.get("SwapFree")).isEqualTo(2097148L * 1024);
        assertThat(values).doesNotContainKeys("garbage", "Bad");
    }

    @Test
    void aReadingNeverFailsEvenWhereProcIsMissing() {
        Resources.Snapshot snapshot = new Resources(() -> "/no/such/folder/at/all").read();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.diskFree()).isNotNull();   // the nearest folder that exists is measured
    }

    @Test
    void readsProcessorTimeAndTimeWaitingForDisksFromProcStat() {
        long[] cpu = Resources.parseCpuStat(List.of("cpu  100 20 30 700 50 5 5 10 0 0", "cpu0 50 10 15 350 25 2 3 5 0 0", "intr 1"));

        assertThat(cpu[0]).isEqualTo(100 + 20 + 30 + 700 + 50 + 5 + 5 + 10);
        assertThat(cpu[1]).isEqualTo(50);
    }

    @Test
    void countsOnlyWholeDisksSoThatPartitionsAreNotCountedTwice() {
        long[] sectors = Resources.parseDiskstats(List.of(
                "   8       0 sda 100 0 1000 5 200 0 4000 9 0 0 0",
                "   8       1 sda1 90 0 900 4 190 0 3900 8 0 0 0",
                " 259       0 nvme0n1 10 0 100 1 20 0 400 2 0 0 0",
                " 259       1 nvme0n1p1 9 0 90 1 19 0 390 2 0 0 0",
                "   7       0 loop0 1 0 8 0 0 0 0 0 0 0 0",
                " 252       0 dm-0 5 0 50 0 5 0 50 0 0 0 0",
                "   8      16 vdb 7 0 70 1 8 0 80 1 0 0 0"));

        assertThat(sectors[0]).isEqualTo(1000 + 100 + 70);
        assertThat(sectors[1]).isEqualTo(4000 + 400 + 80);
    }

    @Test
    void theRatesAppearOnceThereAreTwoReadingsAndNeverFail() throws Exception {
        Resources resources = new Resources(() -> System.getProperty("java.io.tmpdir"));
        Resources.Snapshot first = resources.read();
        assertThat(first.iowaitPercent()).isNull();      // nothing to compare with yet
        Thread.sleep(1100);
        Resources.Snapshot second = resources.read();
        if (java.nio.file.Files.isReadable(java.nio.file.Path.of("/proc/stat"))) {
            assertThat(second.iowaitPercent()).isBetween(0, 100);
            assertThat(second.diskReadPerSecond()).isGreaterThanOrEqualTo(0);
            assertThat(second.diskWritePerSecond()).isGreaterThanOrEqualTo(0);
        }
    }
}
