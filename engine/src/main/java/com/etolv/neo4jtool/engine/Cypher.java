package com.etolv.neo4jtool.engine;

/** Small helpers for building Cypher text safely. */
public final class Cypher {

    private Cypher() {
    }

    /** Quotes a label, relationship type or property name so it is safe to embed in a query. */
    public static String ident(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Identifier must not be empty");
        }
        return "`" + name.replace("`", "``") + "`";
    }
}
