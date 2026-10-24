package com.etolv.neo4jtool.engine.build;

/**
 * What an export does about nodes whose id the target already uses.
 *
 * @param ids     the new id of a node that is written with another id; it also maps the ends of the relationships
 * @param rewrite what to do with property values that are such an id; null leaves every property as it is
 */
public record Renumbering(IdRemap ids, ReferenceRewrite rewrite) {

    private static final Renumbering NONE = new Renumbering(IdRemap.none(), null);

    public static Renumbering none() {
        return NONE;
    }

    public boolean isNone() {
        return ids.isEmpty() && rewrite == null;
    }
}
