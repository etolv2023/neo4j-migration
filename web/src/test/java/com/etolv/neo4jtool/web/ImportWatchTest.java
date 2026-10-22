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

    @Test
    void tellsWhenTheImporterIsGettingReadyAndHowLongThatTookAndWhichStepsAreOver() throws Exception {
        ImportWatch watch = new ImportWatch();
        assertThat(watch.preparingSeconds()).isEqualTo(-1);
        assertThat(watch.preparedSeconds()).isEqualTo(-1);

        boolean sawPreparing = false;
        for (String line : sample()) {
            watch.accept(line);
            if (watch.preparingSeconds() >= 0) {
                sawPreparing = true;
                assertThat(watch.stage()).isNull();           // no step has begun while it is getting ready
            }
        }

        assertThat(sawPreparing).isTrue();
        assertThat(watch.preparingSeconds()).isEqualTo(-1);   // over: it read rows
        assertThat(watch.preparedSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(watch.finished()).extracting(ImportWatch.Done::name).containsExactly("Node import", "Relationship import");
    }

    @Test
    void aSecondImportStartsFromScratch() throws Exception {
        ImportWatch watch = new ImportWatch();
        sample().forEach(watch::accept);
        assertThat(watch.finished()).isNotEmpty();

        watch.accept("Available resources:");

        assertThat(watch.finished()).isEmpty();
        assertThat(watch.stage()).isNull();
        assertThat(watch.preparingSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void writesDurationsTheWayAPersonReadsThem() {
        assertThat(ImportWatch.text(45)).isEqualTo("45 s");
        assertThat(ImportWatch.text(33 * 60 + 12)).isEqualTo("33 min 12 s");
        assertThat(ImportWatch.text(3720)).isEqualTo("1 h 2 min");
    }
}
