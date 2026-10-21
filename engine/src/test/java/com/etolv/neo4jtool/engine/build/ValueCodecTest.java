package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

class ValueCodecTest {

    @Test
    void scalarsKeepTheirTypeAndText() {
        assertThat(ValueCodec.encode(Values.value("a,b\n\"c\""))).isEqualTo(new ValueCodec.Encoded("string", "a,b\n\"c\""));
        assertThat(ValueCodec.encode(Values.value(-9L))).isEqualTo(new ValueCodec.Encoded("long", "-9"));
        assertThat(ValueCodec.encode(Values.value(0.25))).isEqualTo(new ValueCodec.Encoded("double", "0.25"));
        assertThat(ValueCodec.encode(Values.value(true))).isEqualTo(new ValueCodec.Encoded("boolean", "true"));
        assertThat(ValueCodec.encode(Values.value(java.time.LocalDate.of(2024, 2, 29)))).isEqualTo(new ValueCodec.Encoded("date", "2024-02-29"));
    }

    @Test
    void listsBecomeTypedArraysAndEmptyOnesNeedAFixup() {
        assertThat(ValueCodec.encode(Values.value(List.of(1L, 2L)))).isEqualTo(new ValueCodec.Encoded("long[]", "1\u001f2"));
        assertThat(ValueCodec.encode(Values.value(List.of(1.5, 2.5)))).isEqualTo(new ValueCodec.Encoded("double[]", "1.5\u001f2.5"));
        assertThat(ValueCodec.encode(Values.value(List.of()))).isNull();
        assertThat(ValueCodec.encode(Values.value(List.of("has\u001fdelimiter")))).isNull();
    }

    @Test
    void aDateTimeWithANamedZoneNeedsAFixupButAnOffsetDoesNot() {
        assertThat(ValueCodec.encode(Values.value(java.time.ZonedDateTime.of(2024, 5, 1, 12, 0, 0, 0, java.time.ZoneId.of("Africa/Cairo"))))).isNull();
        assertThat(ValueCodec.encode(Values.value(java.time.ZonedDateTime.of(2024, 5, 1, 12, 0, 0, 0, java.time.ZoneOffset.ofHours(3)))).type())
                .isEqualTo("datetime");
    }

    @Test
    void keysThatBreakAColumnHeaderAreNotSafe() {
        assertThat(ValueCodec.isSafeKey("org_id")).isTrue();
        assertThat(ValueCodec.isSafeKey("name with space")).isTrue();
        assertThat(ValueCodec.isSafeKey("a:b")).isFalse();
        assertThat(ValueCodec.isSafeKey("f(x)")).isFalse();
        assertThat(ValueCodec.isSafeKey(" leading")).isFalse();
    }

    @Test
    void fingerprintsDependOnEveryPartOfAnEntityButNotOnTheOrderOfLabelsOrKeys() {
        Map<String, Value> props = Map.of("a", Values.value(1L), "b", Values.value("x"));
        long base = Fingerprint.node(5, List.of("A", "B"), props);

        assertThat(Fingerprint.node(5, List.of("B", "A"), props)).isEqualTo(base);
        assertThat(Fingerprint.node(6, List.of("A", "B"), props)).isNotEqualTo(base);
        assertThat(Fingerprint.node(5, List.of("A"), props)).isNotEqualTo(base);
        assertThat(Fingerprint.node(5, List.of("A", "B"), Map.of("a", Values.value("1"), "b", Values.value("x")))).isNotEqualTo(base);
        assertThat(Fingerprint.relationship(1, 2, "T", props)).isNotEqualTo(Fingerprint.relationship(2, 1, "T", props));
    }

    @Test
    void aPropertyWithSeveralTypesIsAConflictAndOthersStayPlain() {
        GroupPlan plan = new GroupPlan();
        plan.observe("size", "long");
        plan.observe("size", "string");
        plan.observe("name", "string");

        assertThat(plan.plainKeys()).containsExactly("name");
        assertThat(plan.conflictKeys()).containsExactly("size");
        assertThat(plan.knows("size", "long")).isTrue();
        assertThat(plan.knows("size", "double")).isFalse();
    }
}
