package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogFilterTest {

    @Test
    void aRealImportBecomesAShortReadableLog() throws Exception {
        List<String> all = ImportWatchTest.sample();
        List<String> kept = new ArrayList<>();
        ImportWatch watch = new ImportWatch();
        LogFilter filter = new LogFilter(kept::add, watch);
        all.forEach(filter);

        assertThat(all.size()).isGreaterThan(1000);
        assertThat(kept.size()).isLessThan(all.size() / 10);
        assertThat(kept).contains("  1000 CSV files");
        assertThat(kept).noneMatch(line -> line.contains(".csv"));
        assertThat(kept).noneMatch(String::isBlank);
        assertThat(kept).contains("Node import COMPLETED in 2s 17ms");
        // a line of progress every ten percent, without the dots
        assertThat(kept).anyMatch(line -> line.startsWith("  50% "));
        assertThat(kept).noneMatch(line -> line.contains("....."));
        assertThat(kept).noneMatch(line -> line.startsWith("  5% ") || line.startsWith("  15% "));
        // the bar sees every line, whatever is kept
        assertThat(watch.stage().number()).isEqualTo(3);
        assertThat(watch.stage().percent()).isEqualTo(100);
    }

    @Test
    void aWatchThatFailsDoesNotLoseTheLine() {
        List<String> kept = new ArrayList<>();
        ImportWatch broken = new ImportWatch() {
            @Override
            void accept(String line) {
                throw new IllegalStateException("boom");
            }
        };
        new LogFilter(kept::add, broken).accept("Something happened");
        assertThat(kept).containsExactly("Something happened");
    }

    @Test
    void ordinaryLinesPassThroughAsTheyAre() {
        List<String> kept = new ArrayList<>();
        LogFilter filter = new LogFilter(kept::add, new ImportWatch());
        filter.accept("Backup: /var/backups/x.dump");
        filter.accept("  /var/backups/x.dump");
        filter.accept("Export: 12 node layouts and 3 relationship layouts");
        assertThat(kept).containsExactly("Backup: /var/backups/x.dump", "  /var/backups/x.dump",
                "Export: 12 node layouts and 3 relationship layouts");
    }
}
