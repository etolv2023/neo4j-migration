package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.build.Phase;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunStepsTest {

    private static final RunSteps.Context MERGE = new RunSteps.Context("full-source", "target-test", false, false, true, true);

    private static RunSteps.Mark at(long seconds, String phase) {
        return new RunSteps.Mark(phase, seconds * 1000);
    }

    private static List<String> states(List<RunSteps.Step> steps) {
        return steps.stream().map(step -> step.state().name().charAt(0) + " " + step.title()).toList();
    }

    @Test
    void aMergeThatJustBeganShowsTheFirstStepRunningAndTheRestWaiting() {
        List<RunSteps.Step> steps = RunSteps.of(RunSteps.merge(MERGE), List.of(at(0, "Opening 'full-source'")), 5_000, false);

        assertThat(steps).hasSize(11);
        assertThat(states(steps)).startsWith("R Open the databases", "W Back up the target");
        assertThat(steps.getFirst().seconds()).isEqualTo(5);
        assertThat(steps.getLast().title()).isEqualTo("Write the merged dump");
        assertThat(steps).extracting(RunSteps.Step::title).doesNotContain("Load the backup");
    }

    @Test
    void theStepsDoneRunningAndLeftFollowWhatTheEngineReports() {
        List<RunSteps.Mark> marks = List.of(at(0, "Opening 'full-source'"), at(2, "Backing up the target"),
                at(40, "Comparing the selection with the target"), at(60, "Exporting the target to CSV"),
                at(61, "Export, pass 1 of 2: finding out which property types occur"),
                at(300, "Export, pass 2 of 2: writing the CSV files"), at(900, "Exporting the nodes to restore to CSV"),
                at(910, "Building the new database with neo4j-admin import (ids kept)"));
        List<RunSteps.Step> steps = RunSteps.of(RunSteps.merge(MERGE), marks, 1_000_000, false);

        assertThat(states(steps)).containsExactly("D Open the databases", "D Back up the target",
                "D Compare the selection with the target", "D Export the target to CSV", "D Export the nodes to restore",
                "R Build the new database (neo4j-admin import)", "W Finish it on a temporary server",
                "W Compare it with the exported data", "W Stop the temporary server", "W Check the store",
                "W Write the merged dump");
        assertThat(steps.get(3).seconds()).isEqualTo(840);
        assertThat(steps.get(3).detail()).isNull();
        assertThat(steps.get(5).seconds()).isEqualTo(90);
    }

    @Test
    void theSubStepsOfARunningStepAreItsDetail() {
        List<RunSteps.Step> steps = RunSteps.of(RunSteps.merge(MERGE), List.of(at(0, "Opening 'full-source'"),
                at(1, "Backing up the target"), at(2, "Comparing the selection with the target"),
                at(3, "Exporting the target to CSV"), at(4, "Export, pass 2 of 2: writing the CSV files")), 10_000, false);

        assertThat(steps.get(3).state()).isEqualTo(RunSteps.State.RUNNING);
        assertThat(steps.get(3).detail()).isEqualTo("Export, pass 2 of 2: writing the CSV files");
    }

    @Test
    void whenTheFastReaderIsOffTheComparisonComesBeforeStoppingTheServer() {
        List<RunSteps.Mark> marks = List.of(at(0, "Opening 'full-source'"), at(1, "Finishing the new database on a temporary server"),
                at(2, "Comparing the new database with the exported data"), at(3, "Stopping the temporary server"));
        List<RunSteps.Step> steps = RunSteps.of(RunSteps.merge(MERGE), marks, 10_000, false);

        assertThat(states(steps).subList(0, 4)).containsExactly("D Open the databases", "D Finish it on a temporary server",
                "D Compare it with the exported data", "R Stop the temporary server");
    }

    @Test
    void aFailureMarksTheStepItStoppedAt() {
        List<RunSteps.Step> steps = RunSteps.of(RunSteps.merge(MERGE),
                List.of(at(0, "Opening 'full-source'"), at(2, "Backing up the target")), 9_000, true);

        assertThat(states(steps)).startsWith("D Open the databases", "F Back up the target", "W Compare the selection with the target");
        assertThat(steps.get(1).seconds()).isEqualTo(7);
    }

    @Test
    void aFinishedRunShowsHowLongEachStepTookAndNothingWaiting() {
        List<Phase> phases = List.of(new Phase("Backing up the target", 4_000), new Phase("Exporting the target to CSV", 61_000),
                new Phase("Writing the merged dump", 9_000));
        List<RunSteps.Step> steps = RunSteps.finished(RunSteps.merge(MERGE), phases);

        assertThat(steps).extracting(RunSteps.Step::state).containsOnly(RunSteps.State.DONE);
        assertThat(steps).extracting(RunSteps.Step::title).contains("Open the databases", "Check the store");
        RunSteps.Step export = steps.stream().filter(s -> s.title().startsWith("Export the target")).findFirst().orElseThrow();
        assertThat(export.seconds()).isEqualTo(61);
    }

    @Test
    void theStepsDependOnTheSettingsAndOnWhatIsBeingMade() {
        RunSteps.Context swap = new RunSteps.Context("a", "b", true, false, false, false);
        assertThat(RunSteps.merge(swap)).extracting(RunSteps.Def::title).contains("Swap the new database into the data directory");
        assertThat(RunSteps.all(RunSteps.merge(swap), false)).extracting(RunSteps.Step::title).doesNotContain("Check the store");

        RunSteps.Context split = new RunSteps.Context("a", null, false, true, true, true);
        assertThat(RunSteps.all(RunSteps.split(split), false)).extracting(RunSteps.Step::title)
                .contains("Read the selected ids", "Move the data directory into place").doesNotContain("Back up the target");
        assertThat(RunSteps.all(RunSteps.plan(split), true)).hasSize(2);
        assertThat(RunSteps.all(RunSteps.dryRun(MERGE), false)).extracting(RunSteps.Step::title)
                .contains("Compare them with the target", "Analyse the relationships");
    }
}
