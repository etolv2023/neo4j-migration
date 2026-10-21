package com.etolv.neo4jtool.engine.selection;

import java.util.Map;

/** Query text plus its parameters. Values are always passed as parameters, never concatenated. */
public record CypherQuery(String text, Map<String, Object> params) {
}
