package com.etolv.neo4jtool.cli;

import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import picocli.CommandLine.Option;

/**
 * Connection details for one database. Passwords are never taken from the command line (they would show up in the
 * process list and shell history): they are read from an environment variable.
 */
final class ConnectionOptions {

    @Option(names = "--uri", required = true, description = "Bolt URI, for example bolt://localhost:7687")
    String uri;

    @Option(names = "--user", description = "User name (leave out when the server has no authentication)")
    String user;

    @Option(names = "--password-env", description = "Name of the environment variable that holds the password",
            defaultValue = "NEO4J_PASSWORD")
    String passwordEnv;

    @Option(names = "--database", description = "Database name (default: ${DEFAULT-VALUE})", defaultValue = "neo4j")
    String database;

    Neo4jClient open() {
        String password = user == null ? null : System.getenv(passwordEnv);
        if (user != null && password == null) {
            throw new IllegalArgumentException("Environment variable " + passwordEnv + " is not set");
        }
        Neo4jClient client = new Neo4jClient(new ConnectionSettings(uri, user, password, database));
        client.verify();
        return client;
    }
}
