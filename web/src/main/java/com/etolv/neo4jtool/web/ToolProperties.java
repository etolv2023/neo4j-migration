package com.etolv.neo4jtool.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings that must come from the environment. Missing values stop the start-up with a clear message. */
@ConfigurationProperties(prefix = "tool")
public record ToolProperties(String secret, Admin admin) {

    public record Admin(String user, String password) {
    }

    public ToolProperties {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("Set TOOL_SECRET (at least 16 characters): it encrypts the stored Neo4j passwords");
        }
        if (admin == null || admin.user() == null || admin.user().isBlank()
                || admin.password() == null || admin.password().length() < 8) {
            throw new IllegalStateException("Set TOOL_ADMIN_USER and TOOL_ADMIN_PASSWORD (at least 8 characters)");
        }
    }
}
