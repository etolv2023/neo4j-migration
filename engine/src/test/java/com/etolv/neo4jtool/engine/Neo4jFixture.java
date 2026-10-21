package com.etolv.neo4jtool.engine;

import com.etolv.neo4jtool.engine.conn.ConnectionSettings;
import com.etolv.neo4jtool.engine.conn.Neo4jClient;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A throw-away Neo4j server in Docker. The tool itself only reads, so the fixture keeps its own
 * writing driver for building test data.
 */
public final class Neo4jFixture implements AutoCloseable {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    private final Neo4jContainer<?> container;
    private final Driver writer;
    private final Neo4jClient client;

    private Neo4jFixture(Neo4jContainer<?> container) {
        this.container = container;
        this.writer = GraphDatabase.driver(container.getBoltUrl(), AuthTokens.none());
        this.client = new Neo4jClient(new ConnectionSettings(container.getBoltUrl(), null, null, "neo4j"));
        client.verify();
    }

    public static Neo4jFixture start() {
        Neo4jContainer<?> container = new Neo4jContainer<>(DockerImageName.parse(IMAGE))
                .withoutAuthentication()
                .withEnv("NEO4J_server_memory_heap_initial__size", "256m")
                .withEnv("NEO4J_server_memory_heap_max__size", "512m")
                .withEnv("NEO4J_server_memory_pagecache_size", "128m");
        container.start();
        return new Neo4jFixture(container);
    }

    public Neo4jClient client() {
        return client;
    }

    public void run(String cypher) {
        run(cypher, Map.of());
    }

    public void run(String cypher, Map<String, Object> params) {
        try (Session session = writer.session()) {
            session.run(cypher, params).consume();
        }
    }

    public List<Record> query(String cypher, Map<String, Object> params) {
        try (Session session = writer.session()) {
            return session.run(cypher, params).list();
        }
    }

    public List<Record> query(String cypher) {
        return query(cypher, Map.of());
    }

    @Override
    public void close() {
        client.close();
        writer.close();
        container.stop();
    }
}
