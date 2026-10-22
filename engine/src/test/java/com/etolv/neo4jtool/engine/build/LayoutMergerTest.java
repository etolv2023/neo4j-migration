package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class LayoutMergerTest {

    /** A frozen plan: "key:type" pairs, and how many rows the group has. */
    private static GroupPlan plan(long rows, String... columns) {
        GroupPlan plan = new GroupPlan();
        for (long i = 0; i < Math.min(rows, 3); i++) {
            plan.observeRow();
        }
        for (String column : columns) {
            String[] parts = column.split(":");
            plan.observe(parts[0], parts[1]);
        }
        plan.freeze();
        return plan;
    }

    /** Tells the plan which combination of types of its conflicting properties occur: "key=type" pairs per combination. */
    private static GroupPlan variants(GroupPlan plan, long rowsEach, String... combinations) {
        for (String combination : combinations) {
            Map<String, String> types = new TreeMap<>();
            if (!combination.isEmpty()) {
                for (String pair : combination.split(",")) {
                    String[] parts = pair.split("=");
                    types.put(parts[0], parts[1]);
                }
            }
            plan.addVariant(types, rowsEach);
        }
        plan.variantsDone();
        return plan;
    }

    private static GroupPlan.Shape shape(GroupPlan plan, String... conflictPairs) {
        Map<String, String> types = new TreeMap<>();
        for (String pair : conflictPairs) {
            String[] parts = pair.split("=");
            types.put(parts[0], parts[1]);
        }
        return plan.sharedShape(types);
    }

    @Test
    void groupsWithCompatibleColumnsShareOneLayoutThatHasAllTheColumns() {
        GroupPlan a = plan(3, "name:string", "age:long");
        GroupPlan b = plan(2, "name:string", "email:string");
        GroupPlan c = plan(1);

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, c), 100);

        assertThat(result.layouts()).isEqualTo(1);
        assertThat(result.shared()).isEqualTo(3);
        assertThat(result.widest()).isEqualTo(3);
        for (GroupPlan group : List.of(a, b, c)) {
            GroupPlan.Shape shape = shape(group);
            assertThat(shape.keys()).containsExactly("age", "email", "name");
            assertThat(shape.types()).containsEntry("age", "long").containsEntry("email", "string");
        }
        // what a group knew about itself is unchanged: a property it never had is still not one of its own
        assertThat(a.knows("email", "string")).isFalse();
        assertThat(a.knows("age", "long")).isTrue();
        assertThat(a.plainKeys()).containsExactly("age", "name");
    }

    @Test
    void aPropertyWithDifferentTypesInTwoGroupsKeepsThemInDifferentLayouts() {
        GroupPlan a = plan(3, "v:long", "x:string");
        GroupPlan b = plan(2, "v:string", "x:string");
        GroupPlan c = plan(1, "v:long", "y:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, c), 100);

        assertThat(result.layouts()).isEqualTo(2);
        assertThat(shape(a).keys()).containsExactly("v", "x", "y");   // a and c agree on v
        assertThat(shape(c)).isSameAs(shape(a));
        assertThat(shape(b).keys()).containsExactly("v", "x");
        assertThat(shape(b).types()).containsEntry("v", "string");
    }

    @Test
    void aLayoutNeverGetsWiderThanTheLimit() {
        GroupPlan a = plan(5, "a:long", "b:long", "c:long");
        GroupPlan b = plan(4, "d:long", "e:long", "f:long");
        GroupPlan c = plan(3, "a:long", "d:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, c), 4);

        assertThat(result.widest()).isLessThanOrEqualTo(4);
        for (GroupPlan group : List.of(a, b, c)) {
            assertThat(shape(group).keys().size()).isLessThanOrEqualTo(4);
        }
        assertThat(shape(a).keys()).contains("a", "b", "c");
        assertThat(shape(b).keys()).contains("d", "e", "f");
        assertThat(shape(c).keys()).contains("a", "d");
    }

    @Test
    void aGroupWiderThanTheLimitStillGetsItsOwnLayout() {
        GroupPlan wide = plan(5, "a:long", "b:long", "c:long", "d:long", "e:long");
        GroupPlan small = plan(4, "a:long");

        LayoutMerger.merge(List.of(wide, small), 3);

        assertThat(shape(wide).keys()).hasSize(5);
        assertThat(shape(small).keys()).containsExactly("a");
    }

    @Test
    void zeroLeavesEveryGroupAlone() {
        GroupPlan a = plan(3, "name:string");
        GroupPlan b = plan(2, "age:long");
        GroupPlan mixed = variants(plan(2, "v:long", "v:string"), 1, "v=long", "v=string");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, mixed), 0);

        assertThat(result.shared()).isZero();
        assertThat(result.layouts()).isZero();
        assertThat(shape(a)).isNull();
        assertThat(shape(b)).isNull();
        assertThat(shape(mixed, "v=long")).isNull();
    }

    // ---- groups with conflicting types: one item per combination of types ----

    @Test
    void eachCombinationOfAGroupWithConflictsCanJoinALayoutOfCompatibleItems() {
        // "v" is a number on some nodes and text on others; "name" is text everywhere
        GroupPlan mixed = variants(plan(5, "v:long", "v:string", "name:string"), 10, "v=long", "v=string", "");
        GroupPlan numbers = plan(4, "name:string", "v2:long");
        // this group has v as a number only, so it fits where mixed has v as a number
        GroupPlan onlyNumbers = plan(3, "v:long", "age:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(mixed, numbers, onlyNumbers), 100);

        assertThat(result.items()).isEqualTo(5);           // three combinations and two plain groups
        assertThat(result.layouts()).isEqualTo(2);         // v as a number in one, v as text in the other
        GroupPlan.Shape asNumber = shape(mixed, "v=long");
        GroupPlan.Shape asText = shape(mixed, "v=string");
        assertThat(asNumber).isNotSameAs(asText);
        assertThat(asNumber.types()).containsEntry("v", "long");
        assertThat(asText.types()).containsEntry("v", "string");
        assertThat(shape(mixed)).isNotNull();               // no v at all fits anywhere
        assertThat(shape(onlyNumbers)).isSameAs(asNumber);
        assertThat(shape(numbers)).isNotNull();
    }

    @Test
    void manyCombinationsOfSeveralConflictingPropertiesFallIntoAFewLayouts() {
        // three properties with two types each: eight combinations (and the ones with a property missing)
        GroupPlan group = plan(100, "a:long", "a:string", "b:long", "b:string", "c:long", "c:string", "id:long");
        List<String> combinations = new ArrayList<>();
        for (String a : List.of("long", "string", "-")) {
            for (String b : List.of("long", "string", "-")) {
                for (String c : List.of("long", "string", "-")) {
                    List<String> pairs = new ArrayList<>();
                    if (!a.equals("-")) pairs.add("a=" + a);
                    if (!b.equals("-")) pairs.add("b=" + b);
                    if (!c.equals("-")) pairs.add("c=" + c);
                    combinations.add(String.join(",", pairs));
                }
            }
        }
        variants(group, 5, combinations.toArray(new String[0]));

        LayoutMerger.Result result = LayoutMerger.merge(List.of(group), 100);

        assertThat(result.items()).isEqualTo(27);
        assertThat(result.layouts()).isEqualTo(8);   // each of a, b, c is a number or text in a layout: 2 x 2 x 2
        for (String combination : combinations) {
            Map<String, String> types = new TreeMap<>();
            if (!combination.isEmpty()) {
                for (String pair : combination.split(",")) {
                    types.put(pair.split("=")[0], pair.split("=")[1]);
                }
            }
            GroupPlan.Shape shape = group.sharedShape(types);
            types.forEach((key, type) -> assertThat(shape.types()).containsEntry(key, type));
            assertThat(shape.keys()).contains("id");
        }
    }

    @Test
    void aCombinationThatWasNotSeenIsAnErrorWhenTheCombinationsAreKnown() {
        GroupPlan mixed = variants(plan(5, "v:long", "v:string"), 1, "v=long");
        LayoutMerger.merge(List.of(mixed), 100);

        assertThat(shape(mixed, "v=long")).isNotNull();
        assertThatThrownBy(() -> shape(mixed, "v=string"))
                .isInstanceOf(BuildException.class)
                .hasMessageContaining("source changed")
                .hasMessageContaining("v=string");
    }

    @Test
    void aGroupWhoseCombinationsAreNotKnownKeepsALayoutPerCombination() {
        GroupPlan unknown = plan(5, "v:long", "v:string");           // pass 1b did not tell
        GroupPlan overflowed = plan(5, "w:long", "w:string");
        overflowed.overflowVariants();
        overflowed.variantsDone();
        GroupPlan plain = plan(5, "name:string");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(unknown, overflowed, plain), 100);

        assertThat(result.shared()).isEqualTo(1);
        assertThat(shape(unknown, "v=long")).isNull();
        assertThat(shape(overflowed, "w=long")).isNull();
        assertThat(overflowed.variantsComplete()).isFalse();
        assertThat(shape(plain)).isNotNull();
    }

    @Test
    void tooManyCombinationsAreGivenUpOn() {
        GroupPlan group = plan(5, "v:long", "v:string", "id:long");
        for (int i = 0; i <= GroupPlan.MAX_VARIANTS + 1; i++) {
            group.addVariant(Map.of("v", "long", "k" + i, "string"), 1);
        }
        group.variantsDone();

        assertThat(group.variantsComplete()).isFalse();
        assertThat(group.variants()).isEmpty();
    }

    @Test
    void aGroupWithoutConflictsCannotBeGivenAShapeForACombination() {
        GroupPlan mixed = plan(5, "v:long", "v:string");

        assertThatThrownBy(() -> mixed.usePlainShape(new GroupPlan.Shape(0, List.of("v"), Map.of("v", "long"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thousandsOfGroupsBecomeAFewLayouts_andTheResultDoesNotDependOnTheOrderOfTheInput() {
        List<GroupPlan> forward = new ArrayList<>();
        List<GroupPlan> backward = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            // every group has two shared columns and two out of forty others
            forward.add(plan(1 + i % 7, "id:long", "name:string", "p" + (i % 40) + ":string", "q" + ((i * 7) % 40) + ":long"));
            backward.add(plan(1 + i % 7, "id:long", "name:string", "p" + (i % 40) + ":string", "q" + ((i * 7) % 40) + ":long"));
        }
        Collections.reverse(backward);

        LayoutMerger.Result a = LayoutMerger.merge(forward, 100);
        LayoutMerger.Result b = LayoutMerger.merge(backward, 100);

        assertThat(a.layouts()).isLessThan(10);
        assertThat(a.shared()).isEqualTo(3000);
        assertThat(b.layouts()).isLessThan(10);
        assertThat(a.describe()).contains("3000 groups");
    }

    @Test
    void theResultDoesNotDependOnTheOrderOfEqualItems() {
        // many groups with conflicts and the same number of rows: the layouts must come out the same however they are listed
        List<GroupPlan> first = new ArrayList<>();
        List<GroupPlan> second = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            first.add(variants(plan(3, "id:long", "t:long", "t:string", "k" + (i % 9) + ":long"), 2, "t=long", "t=string"));
            second.add(variants(plan(3, "id:long", "t:long", "t:string", "k" + (i % 9) + ":long"), 2, "t=long", "t=string"));
        }
        LayoutMerger.Result a = LayoutMerger.merge(first, 100);
        LayoutMerger.Result b = LayoutMerger.merge(second, 100);

        assertThat(a).isEqualTo(b);
        assertThat(a.layouts()).isEqualTo(2);
    }

    @Test
    void everyGroupKeepsEveryColumnItHad() {
        List<GroupPlan> groups = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            groups.add(plan(1 + i % 5, "id:long", "k" + (i % 13) + ":string", "t" + (i % 5) + ":long", "z" + (i % 3) + ":boolean"));
        }
        List<List<String>> own = groups.stream().map(g -> (List<String>) new ArrayList<>(g.ownPlainTypes().keySet())).toList();
        List<Map<String, String>> ownTypes = groups.stream().map(GroupPlan::ownPlainTypes).toList();

        LayoutMerger.merge(groups, 12);

        for (int i = 0; i < groups.size(); i++) {
            GroupPlan.Shape shape = shape(groups.get(i));
            assertThat(shape.keys()).containsAll(own.get(i));
            assertThat(shape.keys().size()).isLessThanOrEqualTo(12);
            ownTypes.get(i).forEach((key, type) -> assertThat(shape.types()).containsEntry(key, type));
        }
    }
}
