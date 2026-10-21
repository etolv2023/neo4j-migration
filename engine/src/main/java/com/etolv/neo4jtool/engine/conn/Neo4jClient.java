package com.etolv.neo4jtool.engine.conn;

import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * Read-only access to one Neo4j database. Every session it hands out is opened in READ mode,
 * so nothing in this phase of the tool can write to the server.
 */
public final class Neo4jClient implements AutoCloseable {

    /** Rows the driver pulls per round trip while streaming a result. */
    static final int FETCH_SIZE = 10_000;

    private final ConnectionSettings settings;
    private final Driver driver;

    public Neo4jClient(ConnectionSettings settings) {
        this.settings = settings;
        AuthToken auth = settings.hasCredentials()
                ? AuthTokens.basic(settings.username(), settings.password() == null ? "" : settings.password())
                : AuthTokens.none();
        Config config = Config.builder()
                .withMaxConnectionPoolSize(4)
                .withConnectionAcquisitionTimeout(60, TimeUnit.SECONDS)
                .build();
        this.driver = GraphDatabase.driver(settings.uri(), auth, config);
    }

    public void verify() {
        driver.verifyConnectivity();
    }

    public Session readSession() {
        return driver.session(SessionConfig.builder()
                .withDatabase(settings.database())
                .withDefaultAccessMode(AccessMode.READ)
                .withFetchSize(FETCH_SIZE)
                .build());
    }

    /** For example "5.26.29 community". */
    public String serverVersion() {
        try (Session session = readSession()) {
            return session.run("CALL dbms.components() YIELD name, versions, edition "
                            + "WHERE name = 'Neo4j Kernel' RETURN versions[0] + ' ' + edition AS v")
                    .single().get("v").asString();
        }
    }

    public String database() {
        return settings.database();
    }

    public ConnectionSettings settings() {
        return settings;
    }

    @Override
    public void close() {
        driver.close();
    }
}
