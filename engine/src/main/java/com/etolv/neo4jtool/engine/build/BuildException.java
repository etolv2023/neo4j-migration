package com.etolv.neo4jtool.engine.build;

/** A split stopped before it could produce a database it can vouch for. The message says what to do about it. */
public final class BuildException extends RuntimeException {

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
