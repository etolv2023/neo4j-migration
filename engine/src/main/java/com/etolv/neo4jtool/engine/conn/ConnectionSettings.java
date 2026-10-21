package com.etolv.neo4jtool.engine.conn;

import java.util.Objects;

/** Where and how to reach one Neo4j database over bolt. */
public record ConnectionSettings(String uri, String username, String password, String database) {

    public static final String DEFAULT_DATABASE = "neo4j";

    public ConnectionSettings {
        Objects.requireNonNull(uri, "uri");
        if (uri.isBlank()) {
            throw new IllegalArgumentException("Connection uri must not be blank");
        }
        database = (database == null || database.isBlank()) ? DEFAULT_DATABASE : database;
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "ConnectionSettings[uri=" + uri + ", username=" + username + ", database=" + database + "]";
    }
}
