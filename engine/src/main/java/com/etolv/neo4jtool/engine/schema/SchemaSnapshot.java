package com.etolv.neo4jtool.engine.schema;

import java.util.List;

/** Everything the schema explorer shows about one database. */
public record SchemaSnapshot(
        String serverVersion,
        String database,
        long totalNodes,
        long totalRelationships,
        String orgProperty,
        boolean orgBreakdownIncluded,
        List<LabelInfo> labels,
        List<OrgCount> unlabeledByOrg,
        List<RelationshipTypeInfo> relationshipTypes,
        List<String> propertyKeys,
        List<IndexInfo> indexes,
        List<ConstraintInfo> constraints) {

    /** Number of nodes for one value of the org property (null means the node has no such property). */
    public record OrgCount(Object org, long count) {
    }

    public record LabelInfo(String name, long count, List<OrgCount> byOrg) {
    }

    public record RelationshipTypeInfo(String name, long count) {
    }

    public record IndexInfo(String name, String type, String entityType,
                            List<String> labelsOrTypes, List<String> properties, String state) {
    }

    public record ConstraintInfo(String name, String type, String entityType,
                                 List<String> labelsOrTypes, List<String> properties) {
    }
}
