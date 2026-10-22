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
}
