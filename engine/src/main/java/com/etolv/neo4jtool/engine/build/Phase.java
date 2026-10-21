package com.etolv.neo4jtool.engine.build;

/** One finished step of a split or a merge and how long it took. */
public record Phase(String name, long millis) {
}
