package com.etolv.neo4jtool.web;

import java.time.Instant;

/**
 * A database the tool can read: a running server, a dump file, or the data directory of a stopped server.
 * For the last two the tool starts a throw-away server on a copy when it needs to read.
 */
public record Endpoint(long id, String name, Kind kind, String uri, String path, String username, String passwordEnc,
                       String database, Instant createdAt) {

    public enum Kind {
        LIVE("Live server"), DUMP("Dump file"), STOPPED("Stopped server's data directory");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Where it is, for display. */
    public String location() {
        return kind == Kind.LIVE ? uri : path;
    }

    public boolean sameDatabaseAs(Endpoint other) {
        return kind == other.kind && database.equals(other.database)
                && (kind == Kind.LIVE ? uri.equals(other.uri) : path.equals(other.path));
    }
}
