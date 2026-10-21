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
        return new CypherQuery(rows(config), params(config));
    }

    /** One row per selected node with only its id. */
    public static CypherQuery idStream(SelectionConfig config) {
        return wrapped(config, " RETURN id AS id", " RETURN id(n) AS id");
    }

    /** How many nodes are selected and the highest id among them (null when none is). */
    public static CypherQuery nodeStats(SelectionConfig config) {
        return wrapped(config, " RETURN count(id) AS c, max(id) AS maxId", " RETURN count(n) AS c, max(id(n)) AS maxId");
    }

    public static CypherQuery nodeCount(SelectionConfig config) {
        return wrapped(config, " RETURN count(id) AS c", " RETURN count(n) AS c");
    }

    /** How many selected nodes carry each label (a node with several labels counts under each). */
    public static CypherQuery labelCounts(SelectionConfig config) {
        return new CypherQuery("CALL { " + rows(config) + " } UNWIND CASE WHEN size(labels) = 0 THEN ['(no labels)'] ELSE labels END "
                + "AS label RETURN label, count(*) AS c", params(config));
    }

    /**
     * Without neighbours the plain query is used. With them the selected nodes and the neighbours are one UNION, which
     * removes a node that is both.
     */
    private static CypherQuery wrapped(SelectionConfig config, String overRows, String overNodes) {
        if (!followsNeighbours(config)) {
            return new CypherQuery(matchAndWhere(config, "n", "", List.of()) + overNodes, params(config));
        }
        return new CypherQuery("CALL { " + rows(config) + " }" + overRows, params(config));
    }

    private static boolean followsNeighbours(SelectionConfig config) {
        return config.includeNeighbours() && !config.selectsEverything();
    }

    /** Columns: id, labels, org. */
    private static String rows(SelectionConfig config) {
        String org = Cypher.ident(config.orgProperty());
        String selected = matchAndWhere(config, "n", "", List.of())
                + " RETURN id(n) AS id, labels(n) AS labels, n." + org + " AS org";
        if (!followsNeighbours(config)) {
            return selected;
        }
        List<String> extra = new ArrayList<>();
        extra.add("(m." + org + " IS NULL OR m." + org + " = '')");
        if (!config.excludeIds().isEmpty()) {
            extra.add("NOT id(m) IN $excludeIds");
        }
        return selected + " UNION " + matchAndWhere(config, "n", "--(m)", extra)
                + " RETURN id(m) AS id, labels(m) AS labels, m." + org + " AS org";
    }

    /** The selection criteria on {@code variable}; {@code pattern} and {@code extra} describe a neighbour when there is one. */
    private static String matchAndWhere(SelectionConfig config, String variable, String pattern, List<String> extra) {
        StringBuilder sb = new StringBuilder("MATCH (").append(variable);
        if (!config.labels().isEmpty()) {
            sb.append(':').append(config.labels().stream().map(Cypher::ident).collect(Collectors.joining("|")));
        }
        sb.append(')').append(pattern);

        List<String> conditions = new ArrayList<>();
        if (!config.orgIds().isEmpty()) {
            conditions.add(variable + "." + Cypher.ident(config.orgProperty()) + " IN $orgIds");
        }
        List<String> idParts = new ArrayList<>();
        if (!config.ids().isEmpty()) {
            idParts.add("id(" + variable + ") IN $ids");
        }
        if (!config.idRanges().isEmpty()) {
            idParts.add("any(r IN $idRanges WHERE id(" + variable + ") >= r[0] AND id(" + variable + ") <= r[1])");
        }
        if (idParts.size() == 1) {
            conditions.add(idParts.get(0));
        } else if (idParts.size() > 1) {
            conditions.add("(" + String.join(" OR ", idParts) + ")");
        }
        if (!config.excludeIds().isEmpty()) {
            conditions.add("NOT id(" + variable + ") IN $excludeIds");
        }
        conditions.addAll(extra);
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
