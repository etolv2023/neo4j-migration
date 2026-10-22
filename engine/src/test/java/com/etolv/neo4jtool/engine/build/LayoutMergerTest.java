package com.etolv.neo4jtool.engine.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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

    @Test
    void groupsWithCompatibleColumnsShareOneLayoutThatHasAllTheColumns() {
        GroupPlan a = plan(3, "name:string", "age:long");
        GroupPlan b = plan(2, "name:string", "email:string");
        GroupPlan c = plan(1);

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, c), 100);

        assertThat(result.layouts()).isEqualTo(1);
        assertThat(result.merged()).isEqualTo(3);
        assertThat(result.widest()).isEqualTo(3);
        for (GroupPlan group : List.of(a, b, c)) {
            assertThat(group.plainKeys()).containsExactly("age", "email", "name");
            assertThat(group.plainType("age")).isEqualTo("long");
            assertThat(group.plainType("email")).isEqualTo("string");
        }
        // what a group knew about itself is unchanged: a property it never had is still not one of its own
        assertThat(a.knows("email", "string")).isFalse();
        assertThat(a.knows("age", "long")).isTrue();
    }

    @Test
    void aPropertyWithDifferentTypesInTwoGroupsKeepsThemInDifferentLayouts() {
        GroupPlan a = plan(3, "v:long", "x:string");
        GroupPlan b = plan(2, "v:string", "x:string");
        GroupPlan c = plan(1, "v:long", "y:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, c), 100);

        assertThat(result.layouts()).isEqualTo(2);
        assertThat(a.plainKeys()).containsExactly("v", "x", "y");   // a and c agree on v
        assertThat(c.plainKeys()).isEqualTo(a.plainKeys());
        assertThat(b.plainKeys()).containsExactly("v", "x");
        assertThat(b.plainType("v")).isEqualTo("string");
    }

    @Test
    void aLayoutNeverGetsWiderThanTheLimit() {
        GroupPlan a = plan(5, "a:long", "b:long", "c:long");
        GroupPlan b = plan(4, "d:long", "e:long", "f:long");
        GroupPlan c = plan(3, "a:long", "d:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b, c), 4);

        assertThat(result.widest()).isLessThanOrEqualTo(4);
        for (GroupPlan group : List.of(a, b, c)) {
            assertThat(group.plainKeys().size()).isLessThanOrEqualTo(4);
        }
        assertThat(a.plainKeys()).contains("a", "b", "c");
        assertThat(b.plainKeys()).contains("d", "e", "f");
        assertThat(c.plainKeys()).contains("a", "d");
    }

    @Test
    void aGroupWiderThanTheLimitStillGetsItsOwnLayout() {
        GroupPlan wide = plan(5, "a:long", "b:long", "c:long", "d:long", "e:long");
        GroupPlan small = plan(4, "a:long");

        LayoutMerger.merge(List.of(wide, small), 3);

        assertThat(wide.plainKeys()).hasSize(5);
        assertThat(small.plainKeys()).containsExactly("a");
    }

    @Test
    void aGroupWithAConflictingPropertyIsLeftAsItIs() {
        GroupPlan mixed = plan(5, "v:long", "v:string", "name:string");
        GroupPlan plain = plan(4, "name:string", "age:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(mixed, plain), 100);

        assertThat(result.merged()).isEqualTo(1);
        assertThat(mixed.plainKeys()).containsExactly("name");
        assertThat(mixed.conflictKeys()).containsExactly("v");
        assertThat(plain.plainKeys()).containsExactly("age", "name");
        assertThatThrownBy(() -> mixed.useLayout(List.of("name", "v"), java.util.Map.of("name", "string", "v", "long")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zeroLeavesEveryGroupAlone() {
        GroupPlan a = plan(3, "name:string");
        GroupPlan b = plan(2, "age:long");

        LayoutMerger.Result result = LayoutMerger.merge(List.of(a, b), 0);

        assertThat(result.merged()).isZero();
        assertThat(a.plainKeys()).containsExactly("name");
        assertThat(b.plainKeys()).containsExactly("age");
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
        java.util.Collections.reverse(backward);

        LayoutMerger.Result a = LayoutMerger.merge(forward, 100);
        LayoutMerger.Result b = LayoutMerger.merge(backward, 100);

        assertThat(a.layouts()).isLessThan(10);
        assertThat(a.merged()).isEqualTo(3000);
        assertThat(b.layouts()).isLessThan(10);
        assertThat(a.describe()).contains("3000 groups");
    }

    @Test
    void everyGroupKeepsEveryColumnItHad() {
        List<GroupPlan> groups = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            groups.add(plan(1 + i % 5, "id:long", "k" + (i % 13) + ":string", "t" + (i % 5) + ":long", "z" + (i % 3) + ":boolean"));
        }
        List<List<String>> own = groups.stream().map(g -> (List<String>) new ArrayList<>(g.ownPlainTypes().keySet())).toList();

        LayoutMerger.merge(groups, 12);

        for (int i = 0; i < groups.size(); i++) {
            assertThat(groups.get(i).plainKeys()).containsAll(own.get(i));
            assertThat(groups.get(i).plainKeys().size()).isLessThanOrEqualTo(12);
            for (String key : own.get(i)) {
                assertThat(groups.get(i).plainType(key)).isEqualTo(groups.get(i).ownPlainTypes().get(key));
            }
        }
    }
}
