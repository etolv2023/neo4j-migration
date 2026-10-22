package com.etolv.neo4jtool.engine.instance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImportResourcesTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void aMachineWithPlentyOfMemoryGivesTheImportMostOfIt() {
        ImportResources.Plan plan = ImportResources.plan(16 * GIB, 14 * GIB);

        assertThat(plan.heapMb()).isEqualTo(2048);                  // an eighth of the machine
        assertThat(plan.offHeapMb()).isEqualTo(10 * 1024);          // 16 - 4 reserved - 2 heap
    }

    @Test
    void whatTheSystemCanReallyGiveLimitsTheOffHeapMemory() {
        ImportResources.Plan plan = ImportResources.plan(16 * GIB, 4 * GIB);

        assertThat(plan.offHeapMb()).isEqualTo(4 * 1024 - 2048 - 512);
    }

    @Test
    void aSmallMachineStillGetsAWorkingImport() {
        ImportResources.Plan plan = ImportResources.plan(4 * GIB, 2 * GIB);

        assertThat(plan.heapMb()).isEqualTo(1024);
        assertThat(plan.offHeapMb()).isEqualTo(512);
    }

    @Test
    void aBigMachineCapsTheHeap() {
        ImportResources.Plan plan = ImportResources.plan(256 * GIB, 250 * GIB);

        assertThat(plan.heapMb()).isEqualTo(8 * 1024);
        assertThat(plan.offHeapMb()).isEqualTo((256 - 64 - 8) * 1024L);
    }

    @Test
    void theSettingOverridesTheOffHeapMemory() {
        ImportResources.Plan plan = ImportResources.plan(16 * GIB, 14 * GIB);
        ImportFiles files = new ImportFiles(java.nio.file.Path.of("."), java.util.List.of(), java.util.List.of(), ';', 0, "6g");

        assertThat(ImportFiles.offHeap(files, plan)).isEqualTo("6g");
        assertThat(ImportFiles.offHeap(new ImportFiles(java.nio.file.Path.of("."), java.util.List.of(), java.util.List.of(), ';'), plan))
                .isEqualTo("10240m");
    }
}
