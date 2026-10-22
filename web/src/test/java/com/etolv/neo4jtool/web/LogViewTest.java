package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LogViewTest {

    private static JobLog.Line line(String level, String message) {
        return new JobLog.Line(1, "migration:1", Instant.parse("2026-10-22T10:00:00Z"), level, message);
    }

    @Test
    void colourClassesAndImportantLines() {
        assertThat(LogView.cls(line("ERROR", "Run failed: x"))).isEqualTo("ERROR");
        assertThat(LogView.cls(line("STEP", "Exporting the target to CSV"))).isEqualTo("STEP");
        assertThat(LogView.cls(line("INFO", "WARNING Import failed. The store files"))).isEqualTo("WARN");
        assertThat(LogView.cls(line("INFO", "IMPORT FAILED in 3s 831ms."))).isEqualTo("ERROR");
        assertThat(LogView.cls(line("INFO", "Backup: /x"))).isEqualTo("INFO");

        assertThat(LogView.important(line("STEP", "Backing up the target"))).isTrue();
        assertThat(LogView.important(line("INFO", "(1/4) Node import 2026-10-22 10:37:45.927+0000"))).isTrue();
        assertThat(LogView.important(line("INFO", "Node import COMPLETED in 2s 17ms"))).isTrue();
        assertThat(LogView.important(line("INFO", "Export: 12 node layouts and 3 relationship layouts"))).isTrue();
        assertThat(LogView.important(line("INFO", "  Estimated number of nodes: 302.13 k"))).isFalse();
        assertThat(LogView.important(line("INFO", "  1000 CSV files"))).isFalse();
    }

    @Test
    void theTimeHasNoDate() {
        assertThat(LogView.time(Instant.parse("2026-10-22T10:00:00Z"))).matches("\\d\\d:\\d\\d:\\d\\d");
        assertThat(LogView.time(null)).isEmpty();
    }
}
