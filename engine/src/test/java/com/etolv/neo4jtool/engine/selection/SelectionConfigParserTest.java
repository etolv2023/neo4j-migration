package com.etolv.neo4jtool.engine.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionConfigParserTest {

    @Test
    void parsesAllCriteria() {
        SelectionConfig config = SelectionConfigParser.parse("""
                org_property: company_id
                org_ids: [12, 45]
                labels: [Booking, Invoice]
                ids: [1001, 1002]
                id_ranges: [[5000, 9000], [10, 20]]
                exclude_ids: [77]
                """);

        assertThat(config.orgProperty()).isEqualTo("company_id");
        assertThat(config.orgIds()).containsExactly(12L, 45L);
        assertThat(config.labels()).containsExactly("Booking", "Invoice");
        assertThat(config.ids()).containsExactly(1001L, 1002L);
        assertThat(config.idRanges()).containsExactly(List.of(5000L, 9000L), List.of(10L, 20L));
        assertThat(config.excludeIds()).containsExactly(77L);
        assertThat(config.selectsEverything()).isFalse();
    }

    @Test
    void orgIdsKeepStringsAndTurnSmallNumbersIntoLongs() {
        SelectionConfig config = SelectionConfigParser.parse("org_ids: [7, acme, 5000000000]");

        assertThat(config.orgIds()).containsExactly(7L, "acme", 5_000_000_000L);
    }

    @Test
    void blankOrEmptyTextSelectsEverything() {
        assertThat(SelectionConfigParser.parse("").selectsEverything()).isTrue();
        assertThat(SelectionConfigParser.parse("   ").selectsEverything()).isTrue();
        assertThat(SelectionConfigParser.parse("{}").selectsEverything()).isTrue();
        assertThat(SelectionConfigParser.parse("{}").orgProperty()).isEqualTo("org_id");
    }

    @Test
    void excludeIdsAloneDoNotNarrowTheSelection() {
        assertThat(SelectionConfigParser.parse("exclude_ids: [1]").selectsEverything()).isTrue();
    }

    @Test
    void jsonIsAcceptedToo() {
        SelectionConfig config = SelectionConfigParser.parse("{\"org_ids\": [3], \"labels\": [\"A\"]}");

        assertThat(config.orgIds()).containsExactly(3L);
        assertThat(config.labels()).containsExactly("A");
    }

    @Test
    void unknownKeysAreRejectedSoTyposAreNotIgnored() {
        assertThatThrownBy(() -> SelectionConfigParser.parse("org_id: [1]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org_id");
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> SelectionConfigParser.parse("id_ranges: [[9, 3]]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid id range");
        assertThatThrownBy(() -> SelectionConfigParser.parse("id_ranges: [[1, 2, 3]]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pair");
        assertThatThrownBy(() -> SelectionConfigParser.parse("id_ranges: [[-1, 3]]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeIdsAndBlankLabelsAreRejected() {
        assertThatThrownBy(() -> SelectionConfigParser.parse("ids: [-5]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> SelectionConfigParser.parse("labels: ['']"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void writtenYamlCanBeReadBack() {
        SelectionConfig original = SelectionConfigParser.parse("""
                org_ids: [1, two]
                labels: [X]
                id_ranges: [[1, 5]]
                """);

        SelectionConfig again = SelectionConfigParser.parse(SelectionConfigParser.toYaml(original));

        assertThat(again).isEqualTo(original);
    }
}
