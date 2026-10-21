package com.etolv.neo4jtool.engine.dryrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectedNodesTest {

    @Test
    void unsortedInputIsSortedAndKeepsEachNodesData() {
        SelectedNodes nodes = new SelectedNodes();
        nodes.add(30, List.of("C"), 3L);
        nodes.add(10, List.of("A"), 1L);
        nodes.add(20, List.of("B", "Z"), null);
        nodes.seal();

        assertThat(nodes.size()).isEqualTo(3);
        assertThat(nodes.id(0)).isEqualTo(10);
        assertThat(nodes.id(1)).isEqualTo(20);
        assertThat(nodes.id(2)).isEqualTo(30);
        assertThat(nodes.labelsOf(0)).containsExactly("A");
        assertThat(nodes.orgOf(0)).isEqualTo(1L);
        assertThat(nodes.labelsOf(1)).containsExactly("B", "Z");
        assertThat(nodes.orgOf(1)).isNull();
        assertThat(nodes.labelsOf(2)).containsExactly("C");
        assertThat(nodes.orgOf(2)).isEqualTo(3L);
    }

    @Test
    void alreadySortedInputIsLeftAlone() {
        SelectedNodes nodes = new SelectedNodes();
        for (long id = 0; id < 1000; id += 3) {
            nodes.add(id, List.of("L" + (id % 4)), id % 5);
        }
        nodes.seal();

        for (int i = 1; i < nodes.size(); i++) {
            assertThat(nodes.id(i)).isGreaterThan(nodes.id(i - 1));
        }
        assertThat(nodes.orgOf(nodes.indexOf(300))).isEqualTo(0L);
    }

    @Test
    void indexOfFindsSelectedIdsAndReportsOthersAsNegative() {
        SelectedNodes nodes = new SelectedNodes();
        nodes.add(5, List.of(), null);
        nodes.add(9, List.of(), null);
        nodes.seal();

        assertThat(nodes.indexOf(5)).isEqualTo(0);
        assertThat(nodes.indexOf(9)).isEqualTo(1);
        assertThat(nodes.indexOf(7)).isNegative();
        assertThat(nodes.indexOf(100)).isNegative();
    }

    @Test
    void labelSetsAndOrgsAreSharedAndCounted() {
        SelectedNodes nodes = new SelectedNodes();
        nodes.add(1, List.of("A"), 1L);
        nodes.add(2, List.of("A"), 1L);
        nodes.add(3, List.of("A"), 2L);
        nodes.add(4, List.of(), null);
        nodes.seal();

        assertThat(nodes.labelSetCount()).isEqualTo(2);
        assertThat(nodes.orgCount()).isEqualTo(3);
        assertThat(nodes.countsByLabelSet()[nodes.findLabelSet(List.of("A"))]).isEqualTo(3);
        assertThat(nodes.countsByLabelSet()[nodes.findLabelSet(List.of())]).isEqualTo(1);
        assertThat(nodes.countsByOrg()[nodes.findOrg(1L)]).isEqualTo(2);
        assertThat(nodes.countsByOrg()[nodes.findOrg(null)]).isEqualTo(1);
    }

    @Test
    void unknownLabelSetsAndOrgsAreReportedAsMissing() {
        SelectedNodes nodes = new SelectedNodes();
        nodes.add(1, List.of("A"), 1L);
        nodes.seal();

        assertThat(nodes.findLabelSet(List.of("B"))).isEqualTo(-1);
        assertThat(nodes.findOrg(99L)).isEqualTo(-1);
        assertThat(nodes.findOrg(null)).isEqualTo(-1);
    }

    @Test
    void statusIsTrackedPerNode() {
        SelectedNodes nodes = new SelectedNodes();
        nodes.add(1, List.of(), null);
        nodes.add(2, List.of(), null);
        nodes.seal();

        assertThat(nodes.status(0)).isEqualTo(SelectedNodes.UNSET);
        nodes.setStatus(1, DryRunService.CONFLICT);
        assertThat(nodes.status(1)).isEqualTo(DryRunService.CONFLICT);
        assertThat(nodes.status(0)).isEqualTo(SelectedNodes.UNSET);
    }

    @Test
    void idsSliceCopiesTheRequestedRange() {
        SelectedNodes nodes = new SelectedNodes();
        for (long id = 10; id < 20; id++) {
            nodes.add(id, List.of(), null);
        }
        nodes.seal();

        assertThat(nodes.idsSlice(2, 5)).containsExactly(12, 13, 14);
    }

    @Test
    void cannotAddAfterSealing() {
        SelectedNodes nodes = new SelectedNodes();
        nodes.seal();

        assertThatThrownBy(() -> nodes.add(1, List.of(), null)).isInstanceOf(IllegalStateException.class);
    }
}
