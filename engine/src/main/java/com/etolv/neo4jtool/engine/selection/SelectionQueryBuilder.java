package com.etolv.neo4jtool.engine.selection;

import com.etolv.neo4jtool.engine.Cypher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns a {@link SelectionConfig} into read-only Cypher. */
public final class SelectionQueryBuilder {

    private SelectionQueryBuilder() {
    }

    /** One row per selected node: id, labels and the value of the org property. */
    public static CypherQuery nodeStream(SelectionConfig config) {
        String text = matchAndWhere(config)
                + " RETURN id(n) AS id, labels(n) AS labels, n." + Cypher.ident(config.orgProperty()) + " AS org";
        return new CypherQuery(text, params(config));
    }

    public static CypherQuery nodeCount(SelectionConfig config) {
        return new CypherQuery(matchAndWhere(config) + " RETURN count(n) AS c", params(config));
    }

    private static String matchAndWhere(SelectionConfig config) {
        StringBuilder sb = new StringBuilder("MATCH (n");
        if (!config.labels().isEmpty()) {
            sb.append(':').append(config.labels().stream().map(Cypher::ident).collect(Collectors.joining("|")));
        }
        sb.append(')');

        List<String> conditions = new ArrayList<>();
        if (!config.orgIds().isEmpty()) {
            conditions.add("n." + Cypher.ident(config.orgProperty()) + " IN $orgIds");
        }
        List<String> idParts = new ArrayList<>();
        if (!config.ids().isEmpty()) {
            idParts.add("id(n) IN $ids");
        }
        if (!config.idRanges().isEmpty()) {
            idParts.add("any(r IN $idRanges WHERE id(n) >= r[0] AND id(n) <= r[1])");
        }
        if (idParts.size() == 1) {
            conditions.add(idParts.get(0));
        } else if (idParts.size() > 1) {
            conditions.add("(" + String.join(" OR ", idParts) + ")");
        }
        if (!config.excludeIds().isEmpty()) {
            conditions.add("NOT id(n) IN $excludeIds");
        }
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        return sb.toString();
    }

    private static Map<String, Object> params(SelectionConfig config) {
        Map<String, Object> params = new HashMap<>();
        if (!config.orgIds().isEmpty()) {
            params.put("orgIds", config.orgIds());
        }
        if (!config.ids().isEmpty()) {
            params.put("ids", config.ids());
        }
        if (!config.idRanges().isEmpty()) {
            params.put("idRanges", config.idRanges());
        }
        if (!config.excludeIds().isEmpty()) {
            params.put("excludeIds", config.excludeIds());
        }
        return params;
    }
}
