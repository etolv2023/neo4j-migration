package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobLogTest {

    @Test
    void loadProgressIsDroppedExceptTheFinishedLine() {
        assertThat(JobLog.isProgress("Files: 1/38, data:  0.0%")).isTrue();
        assertThat(JobLog.isProgress("2026-10-21 11:59:27 Files: 14/38, data:  0.4%")).isTrue();
        assertThat(JobLog.isProgress("Files: 38/38, data: 99.9%")).isTrue();
        assertThat(JobLog.isProgress("Files: 38/38, data: 100.0%")).isFalse();
        assertThat(JobLog.isProgress("Files: 38/38, data:  100.0%")).isFalse();
        assertThat(JobLog.isProgress("Done: 38 files, 259.2MiB processed in 0.4 seconds.")).isFalse();
        assertThat(JobLog.isProgress("Connecting to bolt://x:7687")).isFalse();
    }
}
