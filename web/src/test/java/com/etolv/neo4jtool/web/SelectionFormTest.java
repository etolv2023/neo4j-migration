package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etolv.neo4jtool.engine.selection.SelectionConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionFormTest {

    private static SelectionForm form(String orgProperty, String orgIds, String labels, String ids, String ranges, String exclude) {
        return new SelectionForm(orgProperty, orgIds, labels, ids, ranges, exclude);
    }

    @Test
    void anEmptyFormIsAnEmptySelectionWithTheDefaultOrganisationProperty() {
        SelectionConfig c = SelectionForm.empty().toConfig("org_id");

        assertThat(c.orgProperty()).isEqualTo("org_id");
        assertThat(c.selectsEverything()).isTrue();
    }

    @Test
    void listsMaySeparateWithCommasSpacesAndNewLines() {
        SelectionConfig c = form("", "1, 2\n3;company", "Booking Invoice", "10,11\n12", "", "").toConfig("org_id");

        assertThat(c.orgIds()).containsExactly(1L, 2L, 3L, "company");
        assertThat(c.labels()).containsExactly("Booking", "Invoice");
        assertThat(c.ids()).containsExactly(10L, 11L, 12L);
    }

    @Test
    void rangesAreWrittenFromDashToValue() {
        SelectionConfig c = form("company_id", "", "", "", "5000-9000, 12 .. 20\n30:40", "7").toConfig("org_id");

        assertThat(c.orgProperty()).isEqualTo("company_id");
        assertThat(c.idRanges()).containsExactly(List.of(5000L, 9000L), List.of(12L, 20L), List.of(30L, 40L));
        assertThat(c.excludeIds()).containsExactly(7L);
    }

    @Test
    void mistakesNameTheFieldAndTheValue() {
        assertThatThrownBy(() -> form("", "", "", "12, abc", "", "").toConfig("org_id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ids").hasMessageContaining("abc");
        assertThatThrownBy(() -> form("", "", "", "", "5000 to 9000", "").toConfig("org_id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Id ranges").hasMessageContaining("5000 to 9000");
        assertThatThrownBy(() -> form("", "", "", "", "", "-3").toConfig("org_id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Excluded ids");
    }

    @Test
    void aBackwardsRangeIsRejectedByTheSelectionItself() {
        assertThatThrownBy(() -> form("", "", "", "", "9000-5000", "").toConfig("org_id"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
