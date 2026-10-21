package com.etolv.neo4jtool.engine.split;

/** A split stopped before it could produce a database it can vouch for. The message says what to do about it. */
public final class SplitException extends RuntimeException {

    public SplitException(String message) {
        super(message);
    }

    public SplitException(String message, Throwable cause) {
        super(message, cause);
    }
}
