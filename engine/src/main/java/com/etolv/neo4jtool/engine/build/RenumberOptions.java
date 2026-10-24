package com.etolv.neo4jtool.engine.build;

import java.util.Set;

/**
 * What a merge does with a selected node whose id the target already uses for another node.
 *
 * @param enabled           give such a node a new id and point everything at it; off, the merge stops instead
 * @param allocation        where the new ids come from
 * @param lists             also replace the old id inside a list property
 * @param includeNewerNodes also change nodes of the organisation that the target has but the source did not have, which were
 *                          made after the source was taken; their property most likely means the node that has the id now, so
 *                          this is off by default
 * @param excluded          properties that are never changed: {@code property} for every node, {@code Label.property} for the
 *                          nodes with that label
 */
public record RenumberOptions(boolean enabled, Allocation allocation, boolean lists, boolean includeNewerNodes, Set<String> excluded) {

    /** Where the new ids are taken. */
    public enum Allocation {
        /** Above the highest id of the source and the target, so no id that was ever used (or can be restored) is reused. */
        ABOVE_HIGHEST,
        /** The smallest ids the new database does not use. Compact, but an id that belonged to a deleted node is used again. */
        FILL_GAPS
    }

    public RenumberOptions {
        allocation = allocation == null ? Allocation.ABOVE_HIGHEST : allocation;
        excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
    }

    public static RenumberOptions off() {
        return new RenumberOptions(false, Allocation.ABOVE_HIGHEST, true, false, Set.of());
    }

    public static RenumberOptions on() {
        return new RenumberOptions(true, Allocation.ABOVE_HIGHEST, true, false, Set.of());
    }
}
