package com.etolv.neo4jtool.engine.instance;

/** A command or server failed. The message includes the tail of its output so it can be shown as it is. */
public class InstanceException extends RuntimeException {

    public InstanceException(String message) {
        super(message);
    }

    public InstanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
