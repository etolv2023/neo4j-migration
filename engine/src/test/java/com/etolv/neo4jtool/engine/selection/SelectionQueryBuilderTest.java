package com.etolv.neo4jtool.engine.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.etolv.neo4jtool.engine.Cypher;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionQueryBuilderTest {

    @Test
    void everythingMatchesAllNodesWithoutFilters() {
        CypherQuery query = SelectionQueryBuilder.nodeStream(SelectionConfig.everything());

        assertThat(query.text()).isEqualTo(
                "MATCH (n) RETURN id(n) AS id, labels(n) AS labels, n.`org_id` AS org");
        assertThat(query.params()).isEmpty();
    }

    @Test
    void orgIdsBecomeAParameterisedInFilter() {
        CypherQuery query = SelectionQueryBuilder.nodeStream(SelectionConfigParser.parse("org_ids: [1, 2]"));

        assertThat(query.text()).contains("MATCH (n) WHERE n.`org_id` IN $orgIds");
        assertThat(query.params()).containsEntry("orgIds", List.of(1L, 2L));
    }

    @Test
    void severalLabelsUseALabelOrExpression() {
        CypherQuery query = SelectionQueryBuilder.nodeStream(SelectionConfigParser.parse("labels: [A, B]"));

        assertThat(query.text()).startsWith("MATCH (n:`A`|`B`)");
        assertThat(query.params()).isEmpty();
    }

    @Test
    void idsAloneUseAnInListSoTheServerCanSeekById() {
        CypherQuery query = SelectionQueryBuilder.nodeStream(SelectionConfigParser.parse("ids: [5, 6]"));

        assertThat(query.text()).contains("WHERE id(n) IN $ids RETURN");
        assertThat(query.params()).containsEntry("ids", List.of(5L, 6L));
    }

    @Test
    void idsAndRangesAreOneOrGroup() {
        CypherQuery query = SelectionQueryBuilder.nodeStream(SelectionConfigParser.parse("""
                ids: [5]
                id_ranges: [[10, 20]]
                """));

        assertThat(query.text()).contains(
                "WHERE (id(n) IN $ids OR any(r IN $idRanges WHERE id(n) >= r[0] AND id(n) <= r[1])) RETURN");
        assertThat(query.params()).containsEntry("idRanges", List.of(List.of(10L, 20L)));
    }

    @Test
    void differentCriteriaAreCombinedWithAnd() {
        CypherQuery query = SelectionQueryBuilder.nodeStream(SelectionConfigParser.parse("""
                org_property: company
                org_ids: [3]
                labels: [Booking]
                exclude_ids: [9]
                """));

        assertThat(query.text()).isEqualTo("MATCH (n:`Booking`) WHERE n.`company` IN $orgIds AND NOT id(n) IN $excludeIds "
                + "RETURN id(n) AS id, labels(n) AS labels, n.`company` AS org");
        assertThat(query.params()).containsOnlyKeys("orgIds", "excludeIds");
    }

    @Test
    void countQueryUsesTheSameFilters() {
        CypherQuery query = SelectionQueryBuilder.nodeCount(SelectionConfigParser.parse("org_ids: [1]"));

        assertThat(query.text()).isEqualTo("MATCH (n) WHERE n.`org_id` IN $orgIds RETURN count(n) AS c");
    }

    @Test
    void namesWithBackticksCannotBreakOutOfTheIdentifier() {
        assertThat(Cypher.ident("a`b")).isEqualTo("`a``b`");

        CypherQuery query = SelectionQueryBuilder.nodeStream(new SelectionConfig(null, null,
                List.of("x`) DETACH DELETE (n //"), null, null, null));

        assertThat(query.text()).startsWith("MATCH (n:`x``) DETACH DELETE (n //`)");
    }
}
