package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportWatchTest {

    static List<String> sample() throws Exception {
        return Files.readAllLines(Path.of("src/test/resources/neo4j-admin-import-output.txt"));
    }

    @Test
    void readsThePercentageOfAProgressLine() {
        assertThat(ImportWatch.percentOf(".......... .......... .......... .......... ..........  45% \u22061ms [2s 5ms]")).isEqualTo(45);
        assertThat(ImportWatch.percentOf(".......... .......... .......... .......... ..........   5% \u22061s 281ms [1s 281ms]")).isEqualTo(5);
        assertThat(ImportWatch.percentOf(".......... .......... .......... .......... .......... 100% \u22060ms [2s 9ms]")).isEqualTo(100);
        assertThat(ImportWatch.percentOf("Node import COMPLETED in 2s 17ms")).isEqualTo(-1);
        assertThat(ImportWatch.percentOf("  Estimated disk space usage: 16.42MiB")).isEqualTo(-1);
        assertThat(ImportWatch.percentOf("Peak memory usage: 100%")).isEqualTo(-1);
    }

    @Test
    void followsTheStepsOfARealImport() throws Exception {
        ImportWatch watch = new ImportWatch();
        List<String> seen = new ArrayList<>();
        for (String line : sample()) {
            watch.accept(line);
            ImportWatch.Stage stage = watch.stage();
            if (stage != null) {
                String text = stage.number() + "/" + stage.of() + " " + stage.name();
                if (seen.isEmpty() || !seen.getLast().equals(text)) {
                    seen.add(text);
                }
            }
        }
        assertThat(seen).containsExactly("1/4 Node import", "2/4 Relationship import", "3/4 Relationship linking");
        assertThat(watch.stage().percent()).isEqualTo(100);
    }

    @Test
    void aLineThatDoesNotFitIsIgnored() {
        ImportWatch watch = new ImportWatch();
        watch.accept(null);
        watch.accept("");
        watch.accept("(x/y) not a step");
        watch.accept("..... 50%");
        assertThat(watch.stage()).isNull();
    }

    @Test
    void estimatesTheTimeLeftOnlyWhenThereIsSomethingToBaseItOn() {
        long tenSecondsAgo = System.currentTimeMillis() - 10_000;
        assertThat(new ImportWatch.Stage(1, 4, "Node import", 50, tenSecondsAgo).etaSeconds()).isBetween(9L, 11L);
        assertThat(new ImportWatch.Stage(1, 4, "Node import", 2, tenSecondsAgo).etaSeconds()).isEqualTo(-1);
        assertThat(new ImportWatch.Stage(1, 4, "Node import", 100, tenSecondsAgo).etaSeconds()).isEqualTo(-1);
    }
}
