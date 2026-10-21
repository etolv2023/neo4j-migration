package com.etolv.neo4jtool.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    private Neo4jContainer<?> backup;
    private Neo4jContainer<?> live;

    @BeforeAll
    void start() {
        backup = container();
        live = container();
        seed(backup);
        seed(live);
        // Live lost ids 0-4 of org 1 and org 2's node 10 now has a different org.
        run(live, "MATCH (n) WHERE id(n) < 5 DETACH DELETE n");
        run(live, "MATCH (n) WHERE id(n) = 10 SET n.org_id = 9");
    }

    @AfterAll
    void stop() {
        backup.stop();
        live.stop();
    }

    private static Neo4jContainer<?> container() {
        Neo4jContainer<?> c = new Neo4jContainer<>(DockerImageName.parse(IMAGE)).withoutAuthentication()
                .withEnv("NEO4J_server_memory_heap_max__size", "512m")
                .withEnv("NEO4J_server_memory_pagecache_size", "128m");
        c.start();
        return c;
    }

    private static void seed(Neo4jContainer<?> c) {
        run(c, "UNWIND range(1, 10) AS i CREATE (:Service {org_id: 1})");
        run(c, "UNWIND range(1, 10) AS i CREATE (:Service {org_id: 2})");
    }

    private static void run(Neo4jContainer<?> c, String cypher) {
        try (Driver d = GraphDatabase.driver(c.getBoltUrl(), AuthTokens.none()); var s = d.session()) {
            s.run(cypher).consume();
        }
    }

    private record Result(int exitCode, String out, String err) {
    }

    private static Result execute(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            var commandLine = Main.commandLine();
            commandLine.setErr(new java.io.PrintWriter(err, true));
            int code = commandLine.execute(args);
            return new Result(code, out.toString(), err.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void schemaPrintsLabelsAndCounts() {
        Result result = execute("schema", "--uri", backup.getBoltUrl());

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("20 nodes").contains("Service").contains("org_id");
    }

    @Test
    void dryRunWithAClashingIdIsBlockedAndWritesTheReport(@TempDir Path dir) throws Exception {
        Path selection = dir.resolve("selection.yaml");
        Files.writeString(selection, "org_ids: [1, 2]\n");
        Path report = dir.resolve("report.json");

        Result result = execute("dry-run", "--source-uri", backup.getBoltUrl(), "--target-uri", live.getBoltUrl(),
                "--selection", selection.toString(), "--out", report.toString());

        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.out()).contains("missing (would be restored)").contains("Result: BLOCKED");
        assertThat(Files.readString(report)).contains("\"gate\" : \"BLOCKED\"").contains("\"conflict\" : 1");
    }

    @Test
    void dryRunWithoutClashesIsClear(@TempDir Path dir) throws Exception {
        Path selection = dir.resolve("selection.yaml");
        Files.writeString(selection, "org_ids: [1]\n");

        Result result = execute("dry-run", "--source-uri", backup.getBoltUrl(), "--target-uri", live.getBoltUrl(),
                "--selection", selection.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Result: CLEAR");
    }

    @Test
    void dryRunWithoutATargetAnalysesTheSelectionOnly(@TempDir Path dir) throws Exception {
        Path selection = dir.resolve("selection.yaml");
        Files.writeString(selection, "org_ids: [2]\n");

        Result result = execute("dry-run", "--source-uri", backup.getBoltUrl(), "--selection", selection.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Target: none").contains("Selected nodes: 10");
    }

    @Test
    void anInvalidSelectionIsAnErrorWithExitCode1(@TempDir Path dir) throws Exception {
        Path selection = dir.resolve("selection.yaml");
        Files.writeString(selection, "org_idz: [2]\n");

        Result result = execute("dry-run", "--source-uri", backup.getBoltUrl(), "--selection", selection.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.err()).contains("Error:").contains("org_idz").doesNotContain("\tat ");
    }
}
