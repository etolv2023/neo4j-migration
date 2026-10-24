package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

class ReferenceRewriteTest {

    private static final IdRemap REMAP = IdRemap.of(new long[] {10, 12, 77}, new long[] {12, 14, 500});

    private static ReferenceRewrite moved() {
        return ReferenceRewrite.forMovedNodes(REMAP, "org_id", true, Set.of());
    }

    private static Map<String, Value> props(Object... keyValues) {
        Map<String, Value> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], Values.value(keyValues[i + 1]));
        }
        return map;
    }

    @Test
    void anIntegerThatIsTheOldIdBecomesTheNewOne() {
        Map<String, Value> result = moved().apply(List.of("Booking"), props("customer", 10L), null);

        assertThat(result.get("customer").asLong()).isEqualTo(12);
    }

    @Test
    void aLongerNumberThatContainsTheIdIsNotTouched() {
        Map<String, Value> in = props("a", 122L, "b", 110L, "c", 1L, "d", 101L, "e", "122", "f", "1012");
        Map<String, Value> result = moved().apply(List.of(), in, null);

        assertThat(result).isSameAs(in);
    }

    @Test
    void aTextThatIsThePlainDigitsBecomesATextOfTheNewId() {
        Map<String, Value> result = moved().apply(List.of(), props("customer", "10"), null);

        assertThat(result.get("customer").asString()).isEqualTo("12");
        assertThat(result.get("customer").type().name()).isEqualTo("STRING");
    }

    @Test
    void textsThatOnlyLookLikeTheIdAreNotTouched() {
        Map<String, Value> in = props("a", "010", "b", " 10", "c", "10 ", "d", "10.0", "e", "+10", "f", "-10", "g", "0x0A", "h", "ten", "i", "",
                "j", "١٠");
        Map<String, Value> result = moved().apply(List.of(), in, null);

        assertThat(result).isSameAs(in);
    }

    @Test
    void aFloatEqualToTheIdIsNotTouched() {
        Map<String, Value> in = props("a", 10.0, "b", 12.0d);
        assertThat(moved().apply(List.of(), in, null)).isSameAs(in);
    }

    @Test
    void everyValueIsLookedUpOnceSoNothingChains() {
        Map<String, Value> result = moved().apply(List.of(), props("x", 10L, "y", 12L, "z", "12", "w", "77"), null);

        assertThat(result.get("x").asLong()).isEqualTo(12);
        assertThat(result.get("y").asLong()).isEqualTo(14);
        assertThat(result.get("z").asString()).isEqualTo("14");
        assertThat(result.get("w").asString()).isEqualTo("500");
    }

    @Test
    void elementsOfAListAreReplacedOneByOneAndTheKindStays() {
        Map<String, Value> result = moved().apply(List.of(), props("ids", List.of(10L, 5L, 12L), "texts", List.of("10", "x", "122")), null);

        assertThat(result.get("ids").asList(Value::asLong)).containsExactly(12L, 5L, 14L);
        assertThat(result.get("texts").asList(Value::asString)).containsExactly("12", "x", "122");
    }

    @Test
    void aListWithoutTheIdIsLeftAlone() {
        Map<String, Value> in = props("ids", List.of(1L, 2L, 3L), "empty", List.of());
        assertThat(moved().apply(List.of(), in, null)).isSameAs(in);
    }

    @Test
    void listsAreLeftAloneWhenSwitchedOff() {
        ReferenceRewrite noLists = ReferenceRewrite.forMovedNodes(REMAP, "org_id", false, Set.of());
        Map<String, Value> in = props("ids", List.of(10L), "single", 10L);

        Map<String, Value> result = noLists.apply(List.of(), in, null);

        assertThat(result.get("ids").asList(Value::asLong)).containsExactly(10L);
        assertThat(result.get("single").asLong()).isEqualTo(12);
    }

    @Test
    void theOrganisationPropertyIsNeverChanged() {
        Map<String, Value> in = props("org_id", 10L);
        assertThat(moved().apply(List.of(), in, null)).isSameAs(in);
    }

    @Test
    void anExcludedPropertyIsLeftAloneForEveryLabelOrForOne() {
        ReferenceRewrite excluded = ReferenceRewrite.forMovedNodes(REMAP, "org_id", true, Set.of("legacy", "Booking.ref"));

        Map<String, Value> booking = excluded.apply(List.of("Booking"), props("legacy", 10L, "ref", 10L, "customer", 10L), null);
        Map<String, Value> invoice = excluded.apply(List.of("Invoice"), props("legacy", 10L, "ref", 10L), null);

        assertThat(booking.get("legacy").asLong()).isEqualTo(10);
        assertThat(booking.get("ref").asLong()).isEqualTo(10);
        assertThat(booking.get("customer").asLong()).isEqualTo(12);
        assertThat(invoice.get("legacy").asLong()).isEqualTo(10);
        assertThat(invoice.get("ref").asLong()).isEqualTo(12);
    }

    @Test
    void theListenerIsToldAboutEveryChangedProperty() {
        List<String> told = new ArrayList<>();
        moved().apply(List.of(), props("a", 10L, "b", "5", "c", List.of(12L)), (property, before, after) -> told.add(property + ":" + before + ">" + after));

        assertThat(told).hasSize(2);
        assertThat(told.get(0)).startsWith("a:");
        assertThat(told.get(1)).startsWith("c:");
    }

    @Test
    void forOrganisationsAppliesOnlyToTheirNodesThatAreEligible() {
        IdSet eligible = IdSet.of(1, 2, 3);
        ReferenceRewrite rewrite = ReferenceRewrite.forOrganisations(REMAP, "org_id", List.of(26124496L), eligible, true, Set.of());

        assertThat(rewrite.appliesTo(1, props("org_id", 26124496L))).isTrue();
        assertThat(rewrite.appliesTo(1, props("org_id", 26124496))).as("an int from another source is the same organisation").isTrue();
        assertThat(rewrite.appliesTo(1, props("org_id", 5L))).as("another organisation").isFalse();
        assertThat(rewrite.appliesTo(1, props("name", "x"))).as("no organisation").isFalse();
        assertThat(rewrite.appliesTo(99, props("org_id", 26124496L))).as("made after the backup").isFalse();
    }

    @Test
    void withoutAnEligibleSetEveryNodeOfTheOrganisationCounts() {
        ReferenceRewrite rewrite = ReferenceRewrite.forOrganisations(REMAP, "org_id", List.of(7L), null, true, Set.of());

        assertThat(rewrite.appliesTo(1_000_000, props("org_id", 7L))).isTrue();
    }

    @Test
    void nothingAppliesWhenNoIdWasRenumbered() {
        ReferenceRewrite rewrite = ReferenceRewrite.forMovedNodes(IdRemap.none(), "org_id", true, Set.of());

        assertThat(rewrite.appliesTo(1, props("org_id", 1L))).isFalse();
    }

    @Test
    void plainIdReadsOnlyPlainDigits() {
        assertThat(ReferenceRewrite.plainId("0")).isEqualTo(0);
        assertThat(ReferenceRewrite.plainId("123456789012345678")).isEqualTo(123456789012345678L);
        assertThat(ReferenceRewrite.plainId("1234567890123456789")).as("19 digits").isEqualTo(-1);
        assertThat(ReferenceRewrite.plainId("00")).isEqualTo(-1);
    }
}
