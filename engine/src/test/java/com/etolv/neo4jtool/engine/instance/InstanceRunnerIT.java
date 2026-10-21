package com.etolv.neo4jtool.engine.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etolv.neo4jtool.engine.instance.InstanceRunner.RunningInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;

/**
 * Makes a stopped database with sparse ids, dumps it, loads the dump into a throw-away server and reads it back -
 * once with the Docker runner and once with a Neo4j installation copied out of the same image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstanceRunnerIT {

    private static final String IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");
    private static final String DATABASE = "sales";

    private Path root;
    private Path dataDirectory;
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    void createStoppedDatabase() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "instance-it-");
        dataDirectory = Files.createDirectories(root.resolve("stopped-data"));
        Files.setPosixFilePermissions(dataDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        String name = "instance-it-seed-" + System.nanoTime();
        Commands.run(List.of("docker", "run", "-d", "--name", name, "-e", "NEO4J_AUTH=none",
                "-e", "NEO4J_initial_dbms_default__database=" + DATABASE, "-e", "NEO4J_server_memory_heap_max__size=512m",
                "-e", "NEO4J_server_memory_pagecache_size=128m", "-p", "127.0.0.1::7687",
                "-v", dataDirectory.toAbsolutePath() + ":/data", IMAGE), Map.of(), null, log::add);
        try {
            String port = Commands.output(List.of("docker", "port", name, "7687/tcp")).lines().findFirst().orElseThrow();
            String uri = "bolt://127.0.0.1:" + port.substring(port.lastIndexOf(':') + 1);
            BoltWait.until(uri, Duration.ofMinutes(3), () -> null);
            try (Driver driver = GraphDatabase.driver(uri, AuthTokens.none()); var session = driver.session(
                    org.neo4j.driver.SessionConfig.forDatabase(DATABASE))) {
                // Ids 0..4 are created, 2 and 3 are deleted: the dump must come back with 0, 1, 4, 5, 6.
                session.run("UNWIND range(0, 4) AS i CREATE (:Service {org_id: 1, n: i})").consume();
                session.run("MATCH (n) WHERE id(n) IN [2, 3] DELETE n").consume();
                session.run("UNWIND range(5, 6) AS i CREATE (:Booking {org_id: 2, n: i})").consume();
            }
        } finally {
            Commands.run(List.of("docker", "stop", "-t", "60", name), Map.of(), null, log::add);
            Commands.run(List.of("docker", "rm", name), Map.of(), null, log::add);
        }
    }

    @AfterAll
    void cleanUp() {
        // Files were written by a container, possibly as another user.
        Commands.run(List.of("docker", "run", "--rm", "--entrypoint", "sh", "-v", root.getParent().toAbsolutePath() + ":/w",
                IMAGE, "-c", "rm -rf /w/" + root.getFileName()), Map.of(), null, s -> { });
    }

    @Test
    void dockerRunnerDumpsAStoppedDirectoryWithoutChangingItAndServesTheDump() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-docker"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));
        String before = listing(dataDirectory);
        Path dumpDir = Files.createDirectories(root.resolve("dump-docker"));

        Path dump = runner.dumpStoppedDatabase(dataDirectory, DATABASE, dumpDir, log::add);

        assertThat(dump).isRegularFile().hasFileName(DATABASE + ".dump");
        assertThat(listing(dataDirectory)).as("the stopped data directory").isEqualTo(before);

        String containerName;
        try (RunningInstance instance = runner.startFromDump(dump, DATABASE, log::add)) {
            assertThat(instance.boltUri()).startsWith("bolt://127.0.0.1:");
            assertSparseIds(instance.boltUri());
            containerName = Commands.output(List.of("docker", "ps", "--filter", "name=neo4j-migration-", "--format", "{{.Names}}"));
            assertThat(containerName).isNotBlank();
        }
        assertThat(Commands.output(List.of("docker", "ps", "-a", "--filter", "name=neo4j-migration-", "--format", "{{.Names}}")))
                .as("containers left behind").isEmpty();
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).as("work directory after closing").isEmpty();
        }
    }

    @Test
    void aStoppedDirectoryCanBeServedInOneStepAndNothingIsLeftBehind() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-stopped"));
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));
        String before = listing(dataDirectory);

        try (RunningInstance instance = runner.startFromStoppedDirectory(dataDirectory, DATABASE, work, log::add)) {
            assertSparseIds(instance.boltUri());
        }

        assertThat(listing(dataDirectory)).isEqualTo(before);
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    @Test
    void aDatabaseThatIsNotInTheDataDirectoryIsReportedClearly() throws Exception {
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, Files.createDirectories(root.resolve("w2")), null));

        assertThatThrownBy(() -> runner.dumpStoppedDatabase(dataDirectory, "nothing", root, log::add))
                .isInstanceOf(InstanceException.class).hasMessageContaining("There is no database 'nothing'");
    }

    @Test
    void aFileThatIsNotADumpFailsWithNeo4jsOwnMessageAndLeavesNothingBehind() throws Exception {
        Path work = Files.createDirectories(root.resolve("work-bad"));
        Path notADump = Files.writeString(root.resolve("bad.dump"), "this is not a dump");
        InstanceRunner runner = new DockerRunner(settings(InstanceSettings.Runner.DOCKER, work, null));

        assertThatThrownBy(() -> runner.startFromDump(notADump, DATABASE, log::add))
                .isInstanceOf(InstanceException.class).hasMessageContaining("failed with exit code");
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    @Test
    void localRunnerDoesTheSameWithAnInstallationOnThisMachine() throws Exception {
        Path home = root.resolve("neo4j-home");
        String container = "instance-it-copy-" + System.nanoTime();
        Commands.run(List.of("docker", "create", "--name", container, IMAGE), Map.of(), null, log::add);
        try {
            Commands.run(List.of("docker", "cp", container + ":/var/lib/neo4j", home.toString()), Map.of(), null, log::add);
        } finally {
            Commands.run(List.of("docker", "rm", container), Map.of(), null, log::add);
        }
        Path work = Files.createDirectories(root.resolve("work-local"));
        InstanceRunner runner = new LocalRunner(settings(InstanceSettings.Runner.LOCAL, work, home));
        Path dumpDir = Files.createDirectories(root.resolve("dump-local"));

        Path dump = runner.dumpStoppedDatabase(dataDirectory, DATABASE, dumpDir, log::add);

        assertThat(dump).isRegularFile();
        try (RunningInstance instance = runner.startFromDump(dump, DATABASE, log::add)) {
            assertSparseIds(instance.boltUri());
        }
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.toList()).as("work directory after closing").isEmpty();
        }
    }

    @Test
    void localRunnerRejectsADirectoryThatIsNotANeo4jInstallation() {
        assertThatThrownBy(() -> new LocalRunner(settings(InstanceSettings.Runner.LOCAL, root, root)))
                .isInstanceOf(InstanceException.class).hasMessageContaining("bin/neo4j-admin is missing");
    }

    private void assertSparseIds(String uri) {
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.none());
             var session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(DATABASE))) {
            List<Long> ids = session.run("MATCH (n) RETURN id(n) AS id ORDER BY id").list(r -> r.get("id").asLong());
            assertThat(ids).containsExactly(0L, 1L, 4L, 5L, 6L);
            Record booking = session.run("MATCH (n:Booking) WHERE id(n) = 6 RETURN n.org_id AS org").single();
            assertThat(booking.get("org").asLong()).isEqualTo(2);
        }
    }

    private static InstanceSettings settings(InstanceSettings.Runner runner, Path work, Path home) {
        return new InstanceSettings(runner, IMAGE, home, work, "512m", "128m", Duration.ofMinutes(5));
    }

    /** Every file with its size and modification time: if this is equal, nothing was written. */
    private static String listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.sorted().map(p -> {
                try {
                    return dir.relativize(p) + " " + Files.size(p) + " " + Files.getLastModifiedTime(p).toMillis()
                            + " " + Files.getAttribute(p, "unix:uid");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).collect(Collectors.joining("\n"));
        }
    }
}
