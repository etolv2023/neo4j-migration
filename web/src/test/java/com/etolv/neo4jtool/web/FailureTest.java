package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureTest {

    private static Failure run(String text, String step) {
        return Failure.explain(text, Failure.Stage.RUN, step);
    }

    @Test
    void aLongArgumentListIsNamedAsSuch() {
        Failure f = run("neo4j-admin failed with exit code 126:\n/bin/sh: Argument list too long", "Importing the CSV files");
        assertThat(f.title()).contains("too long");
        assertThat(f.step()).isEqualTo("Importing the CSV files");
        assertThat(f.technical()).contains("Argument list too long");
        assertThat(f.safe()).isTrue();
    }

    @Test
    void aMismatchAfterTheImportSaysTheNewDatabaseWasThrownAway() {
        Failure f = run("The nodes of the new database differ from the ones that were exported: 3 differ", "Checking the new database");
        assertThat(f.title()).contains("does not match");
        assertThat(f.todo()).contains("Nothing was lost");
    }

    @Test
    void aChangedTargetCarriesItsOwnDataNote() {
        Failure f = run("The files of 'prod' changed after the backup was taken. Is its server running?", "Swapping the new database into place");
        assertThat(f.title()).contains("changed after its backup");
        assertThat(f.data()).startsWith("Nothing was swapped");
        assertThat(f.safe()).isTrue();
    }

    @Test
    void groupsOfAPatternFillTheTexts() {
        Failure f = run("12 selected ids are used by a different node on the target", "Comparing");
        assertThat(f.what()).startsWith("12 of the selected ids");
    }

    @Test
    void theSwapStepNeverClaimsTheDataIsSafe() {
        Failure f = run("something this table does not know", "Swapping the new database into the data directory");
        assertThat(f.safe()).isFalse();
        assertThat(f.data()).contains("Check before you go on");
    }

    @Test
    void planAndDryRunOnlyRead() {
        assertThat(Failure.explain("x", Failure.Stage.PLAN, "Counting").safe()).isTrue();
        assertThat(Failure.explain("x", Failure.Stage.DRY_RUN, null).data()).contains("only reads");
    }

    @Test
    void anUnknownMessageStillSaysWhereItStoppedAndKeepsTheOriginalWhole() {
        Failure f = run("Boom, line 1\nline 2", "Importing the CSV files");
        assertThat(f.title()).contains("Importing the CSV files");
        assertThat(f.todo()).contains("Copy");
        assertThat(f.technical()).isEqualTo("Boom, line 1\nline 2");
    }

    @Test
    void aStopBeforeTheFirstStepIsSaidSo() {
        assertThat(run("x", null).step()).contains("Before the first step");
    }

    @Test
    void stagesComeFromTheMarkerLine() {
        assertThat(Failure.Stage.of("Planning started")).isEqualTo(Failure.Stage.PLAN);
        assertThat(Failure.Stage.of("Dry run started")).isEqualTo(Failure.Stage.DRY_RUN);
        assertThat(Failure.Stage.of("Rollback started")).isEqualTo(Failure.Stage.ROLLBACK);
        assertThat(Failure.Stage.of("Run started")).isEqualTo(Failure.Stage.RUN);
        assertThat(Failure.Stage.of(null)).isEqualTo(Failure.Stage.RUN);
    }

    @Test
    void describeKeepsTheCausesOnce() {
        Exception e = new IllegalStateException("outer", new RuntimeException("inner cause"));
        assertThat(Failure.describe(e)).isEqualTo("outer\nCaused by: RuntimeException: inner cause");
        assertThat(Failure.describe(new RuntimeException((String) null))).isEqualTo("RuntimeException");
    }
}
