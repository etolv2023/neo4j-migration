package com.etolv.neo4jtool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The dashboard against a real MySQL (Flyway creates the tables) and two real Neo4j servers:
 * a "backup" with everything and a "live" that lost some nodes and has one clashing id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebIT {

    private static final String NEO4J_IMAGE = System.getProperty("neo4j.image", "neo4j:5.26.29-community");

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("neo4j_tool");
    static final Neo4jContainer<?> BACKUP = neo4j();
    static final Neo4jContainer<?> LIVE = neo4j();

    private static Neo4jContainer<?> neo4j() {
        return new Neo4jContainer<>(DockerImageName.parse(NEO4J_IMAGE)).withoutAuthentication()
                .withEnv("NEO4J_server_memory_heap_max__size", "512m")
                .withEnv("NEO4J_server_memory_pagecache_size", "128m");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MYSQL.start();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("tool.secret", () -> "integration-test-secret");
        registry.add("tool.admin.user", () -> "admin");
        registry.add("tool.admin.password", () -> "correct horse battery");
    }

    @BeforeAll
    static void seedNeo4j() {
        BACKUP.start();
        LIVE.start();
        for (Neo4jContainer<?> db : List.of(BACKUP, LIVE)) {
            run(db, "UNWIND range(1, 10) AS i CREATE (:Service {org_id: 1})");
            run(db, "UNWIND range(1, 10) AS i CREATE (:Service {org_id: 2})");
            run(db, "MATCH (a) WHERE id(a) = 10 MATCH (b) WHERE id(b) = 11 CREATE (a)-[:NEXT]->(b)");
        }
        run(LIVE, "MATCH (n) WHERE id(n) < 3 DETACH DELETE n");            // lost org 1 ids 0-2
        run(LIVE, "MATCH (n) WHERE id(n) = 15 SET n.org_id = 9");           // id 15 now holds another node
    }

    private static void run(Neo4jContainer<?> db, String cypher) {
        try (Driver driver = GraphDatabase.driver(db.getBoltUrl(), AuthTokens.none()); var session = driver.session()) {
            session.run(cypher).consume();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired EndpointStore endpoints;
    @Autowired MigrationStore migrations;
    @Autowired SettingsStore settings;
    @Autowired SecretBox secrets;
    @Autowired ReportStore reports;
    @Autowired UserStore users;
    @Autowired JobLog jobLog;

    private static final org.springframework.test.web.servlet.request.RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Test
    @Order(1)
    void everythingExceptTheLoginPageNeedsALogin() throws Exception {
        for (String path : List.of("/", "/migrations", "/migrations/new", "/endpoints", "/logs", "/settings")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        }
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void loginChecksThePasswordAndEveryAttemptIsRecorded() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", "wrong").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").param("username", "admin").param("password", "correct horse battery").with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
        mvc.perform(post("/endpoints").param("name", "x")).andExpect(status().isForbidden());     // no CSRF token

        assertThat(jdbc.sql("SELECT action FROM audit_log").query(String.class).list()).contains("login", "login_failed");
        mvc.perform(get("/logs").param("tab", "logins").with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("login_failed")));
        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE username = 'admin'").query(String.class).single())
                .startsWith("$2").doesNotContain("correct horse");
    }

    @Test
    @Order(3)
    void endpointsAreSavedAndPasswordsAreStoredEncrypted() throws Exception {
        addEndpoint("backup", "LIVE", BACKUP.getBoltUrl(), "", "");
        addEndpoint("live", "LIVE", LIVE.getBoltUrl(), "", "");
        addEndpoint("secured", "LIVE", "bolt://secured.invalid:7687", "neo4j", "s3cret-pass-phrase");
        addEndpoint("backup", "LIVE", BACKUP.getBoltUrl(), "", "");        // duplicate name

        assertThat(endpoints.all()).extracting(Endpoint::name).containsExactly("backup", "live", "secured");
        String stored = jdbc.sql("SELECT password_enc FROM endpoints WHERE name = 'secured'").query(String.class).single();
        assertThat(stored).isNotBlank().doesNotContain("s3cret");
        assertThat(secrets.decrypt(stored)).isEqualTo("s3cret-pass-phrase");
        assertThat(jdbc.sql("SELECT password_enc IS NULL FROM endpoints WHERE name = 'backup'").query(Boolean.class).single()).isTrue();

        mvc.perform(get("/endpoints").with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("s3cret"))))
                .andExpect(content().string(containsString("Live server")));
        mvc.perform(post("/endpoints/" + endpointId("backup") + "/check").with(ADMIN).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("message", containsString("Connected, Neo4j")));
        mvc.perform(post("/endpoints/" + endpointId("secured") + "/check").with(ADMIN).with(csrf()))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @Order(4)
    void dumpsAndDataDirectoriesAreCheckedWhenTheyAreAdded() throws Exception {
        Path folder = Files.createTempDirectory("webit");
        Path dump = Files.writeString(folder.resolve("neo4j.dump"), "not a real dump");
        Path dataDir = Files.createDirectories(folder.resolve("data/databases/neo4j"));

        mvc.perform(post("/endpoints").param("name", "no file").param("kind", "DUMP").param("location", folder.resolve("missing.dump").toString())
                .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString("There is no file")));
        mvc.perform(post("/endpoints").param("name", "wrong dir").param("kind", "STOPPED").param("location", folder.toString())
                .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString("data directory")));
        assertThat(endpoints.all()).extracting(Endpoint::name).doesNotContain("no file", "wrong dir");

        addEndpoint("a dump", "DUMP", dump.toString(), "ignored", "ignored");
        addEndpoint("a stopped server", "STOPPED", folder.resolve("data").toString(), "", "");
        assertThat(dataDir).isDirectory();
        assertThat(endpoints.find(endpointId("a dump")).orElseThrow().passwordEnc()).as("passwords belong to live servers only").isNull();

        mvc.perform(post("/endpoints/" + endpointId("a dump") + "/check").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("message", containsString("Found")));
        mvc.perform(get("/endpoints").with(ADMIN)).andExpect(content().string(containsString(dump.toString())));
    }

    @Test
    @Order(5)
    void schemaPageShowsLabelsAndOrgBreakdown() throws Exception {
        mvc.perform(get("/endpoints/" + endpointId("backup") + "/schema").param("orgBreakdown", "true").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Service")))
                .andExpect(content().string(containsString("NEXT")))
                .andExpect(content().string(containsString("2: 10")));
    }

    @Test
    @Order(6)
    void aServerThatCannotBeReachedShowsTheErrorInsteadOfFailing() throws Exception {
        mvc.perform(get("/endpoints/" + endpointId("secured") + "/schema").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"flash err\"")));
    }

    @Test
    @Order(7)
    void aMigrationNeedsAName_aSource_andAValidSelection() throws Exception {
        long before = migrations.all().size();
        long backup = endpointId("backup");

        for (var bad : List.of(
                Map.of("name", "", "source", String.valueOf(backup)),
                Map.of("name", "no source"),
                Map.of("name", "same", "source", String.valueOf(backup), "target", String.valueOf(backup)),
                Map.of("name", "bad ids", "source", String.valueOf(backup), "ids", "12, abc"),
                Map.of("name", "bad range", "source", String.valueOf(backup), "idRanges", "5000 to 9000"))) {
            var request = post("/migrations").with(ADMIN).with(csrf());
            for (var e : bad.entrySet()) {
                request = request.param(e.getKey(), e.getValue());
            }
            mvc.perform(request).andExpect(status().isOk()).andExpect(content().string(containsString("class=\"flash err\"")));
        }
        assertThat((long) migrations.all().size()).isEqualTo(before);
    }

    @Test
    @Order(8)
    void planThenDryRun_findsTheClashAndBlocks() throws Exception {
        long id = createMigration("org 2 back to live", "backup", "live", Map.of("orgIds", "2"));

        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("org 2 back to live")));
        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("plan first")));     // the order of the flow is enforced

        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf())).andExpect(redirectedUrl("/migrations/" + id));
        Migration planned = awaitState(id, Migration.State.PLANNED);
        MigrationPlan plan = com.etolv.neo4jtool.engine.Json.read(planned.planJson(), MigrationPlan.class);
        assertThat(plan.selectedNodes()).isEqualTo(10);
        assertThat(plan.mode()).isEqualTo("MERGE");
        assertThat(plan.from().nodes()).isEqualTo(20);
        assertThat(plan.steps()).anyMatch(s -> !s.available());

        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf())).andExpect(redirectedUrl("/migrations/" + id));
        Migration done = awaitState(id, Migration.State.DRY_RUN_DONE);

        ReportStore.Row row = reports.find(done.dryRunId()).orElseThrow();
        assertThat(row.gate()).isEqualTo("BLOCKED");
        assertThat(row.selectedNodes()).isEqualTo(10);
        assertThat(row.sameNodes()).isEqualTo(9);
        assertThat(row.conflictNodes()).isEqualTo(1);
        assertThat(row.migrationId()).isEqualTo(id);

        mvc.perform(get("/dry-runs/" + row.id()).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Blocked")))
                .andExpect(content().string(containsString("Id used by a different node")));
        mvc.perform(get("/dry-runs/" + row.id() + "/report.json").with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"selected_nodes\"")));
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(content().string(containsString(">BLOCKED<")));
        assertThat(jdbc.sql("SELECT message FROM job_logs WHERE scope = ?").param("migration:" + id).query(String.class).list())
                .anyMatch(m -> m.contains("Plan ready")).anyMatch(m -> m.contains("Dry run finished: BLOCKED"));
        mvc.perform(get("/logs").param("tab", "jobs").with(ADMIN)).andExpect(content().string(containsString("Plan ready")));
    }

    @Test
    @Order(9)
    void orgOneShowsTheNodesLostOnTheLiveDatabase() throws Exception {
        long id = createMigration("org 1", "backup", "live", Map.of("orgIds", "1"));
        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        awaitState(id, Migration.State.PLANNED);
        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf()));
        Migration done = awaitState(id, Migration.State.DRY_RUN_DONE);

        ReportStore.Row row = reports.find(done.dryRunId()).orElseThrow();
        assertThat(row.gate()).isEqualTo("CLEAR");
        assertThat(row.missingNodes()).isEqualTo(3);
        assertThat(row.sameNodes()).isEqualTo(7);
    }

    @Test
    @Order(10)
    void idsRangesAndLabelsReachTheEngine_andSplittingNeedsNoTarget() throws Exception {
        long id = createMigration("split", "backup", null, Map.of("idRanges", "0-4", "ids", "15", "excludeIds", "2", "labels", "Service"));
        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        Migration planned = awaitState(id, Migration.State.PLANNED);

        MigrationPlan plan = com.etolv.neo4jtool.engine.Json.read(planned.planJson(), MigrationPlan.class);
        assertThat(plan.mode()).isEqualTo("SPLIT");
        assertThat(plan.selectedNodes()).isEqualTo(5);      // 0,1,3,4 and 15; id 2 is excluded
        assertThat(plan.to()).isNull();
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(status().isOk());
    }

    @Test
    @Order(11)
    void aFailedJobGoesBackToItsStateWithTheReason() throws Exception {
        long bad = endpointId("a dump");           // the file is not a dump, so loading it fails
        long id = migrations.create("broken", endpoints.find(bad).orElseThrow(), null, "org_ids: [1]\n");

        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        Migration after = awaitState(id, Migration.State.DRAFT, Duration.ofSeconds(180));

        assertThat(after.lastError()).isNotBlank();
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("The last step failed")));
        assertThat(jdbc.sql("SELECT level FROM job_logs WHERE scope = ?").param("migration:" + id).query(String.class).list())
                .contains("ERROR");
    }

    @Test
    @Order(12)
    void aMigrationThatIsWorkingCannotBeStartedTwiceOrDeleted() throws Exception {
        long id = createMigration("busy", "backup", "live", Map.of("orgIds", "1"));
        migrations.start(id, Migration.State.PLANNING);

        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("already working")));
        mvc.perform(post("/migrations/" + id + "/delete").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("working right now")));
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(content().string(not(containsString("http-equiv=\"refresh\""))))
                .andExpect(content().string(containsString("id=\"job-progress\"")))
                .andExpect(content().string(containsString("data-busy=\"true\"")))
                .andExpect(content().string(containsString("data-migration=\"" + id + "\"")))
                .andExpect(content().string(containsString("data-steps=\"run\"")))
                .andExpect(content().string(containsString("/live.js")))
                .andExpect(content().string(containsString("data-copy=\"#job-log\"")));
        mvc.perform(get("/migrations").with(ADMIN)).andExpect(content().string(containsString("busy-overlay")));
        assertThat(migrations.find(id)).isPresent();

        assertThat(migrations.failInterrupted()).isEqualTo(1);        // what a restart does
        assertThat(migrations.find(id).orElseThrow().state()).isEqualTo(Migration.State.DRAFT);
        mvc.perform(post("/migrations/" + id + "/delete").with(ADMIN).with(csrf())).andExpect(redirectedUrl("/migrations"));
        assertThat(migrations.find(id)).isEmpty();
    }

    @Test
    @Order(13)
    void aDryRunLeftRunningByARestartIsMarkedFailed() {
        jdbc.sql("INSERT INTO dry_run_reports (source_name, selection_yaml, options_json, status) VALUES ('a', 'x', '{}', 'RUNNING')").update();

        assertThat(reports.failInterrupted()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM dry_run_reports WHERE source_name = 'a'").query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    @Order(14)
    void settingsAreSavedValidatedAndUsed() throws Exception {
        mvc.perform(get("/settings").with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("neo4j:5.26.29-community")));
        assertThat(settings.get().includeNeighbours()).as("on by default").isTrue();
        mvc.perform(get("/migrations/new").with(ADMIN))
                .andExpect(content().string(containsString("name=\"includeNeighbours\" value=\"true\" checked")));

        mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "50").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("at least 100")));
        mvc.perform(settingsForm("LOCAL", "", "org_id", "500").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("installation")));
        assertThat(settings.get().batchSize()).isEqualTo(10_000);
        mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "500", Map.of("workers", "0")).with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("Reading threads")));
        mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "500", Map.of("maxJobs", "0")).with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("at the same time")));

        mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "company_id", "500").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("message", "Settings saved"));
        assertThat(settings.get().orgProperty()).isEqualTo("company_id");
        assertThat(settings.get().batchSize()).isEqualTo(500);
        assertThat(settings.get().workers()).isEqualTo(3);
        assertThat(settings.get().importThreads()).isEqualTo(2);
        assertThat(settings.get().readerPageCache()).isEqualTo("256m");
        assertThat(settings.get().fastReader()).isTrue();
        assertThat(settings.get().includeNeighbours()).as("the form had the box unticked").isFalse();
        mvc.perform(get("/migrations/new").with(ADMIN))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"includeNeighbours\" value=\"true\" checked"))));

        // the new default shows up where a selection is typed
        mvc.perform(get("/migrations/new").with(ADMIN)).andExpect(content().string(containsString("company_id")));
        mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "10000").with(ADMIN).with(csrf()));
        assertThat(settings.get().orgProperty()).isEqualTo("org_id");
    }

    @Test
    @Order(15)
    void passwordChangeNeedsTheCurrentPassword() throws Exception {
        String hash = users.loadUserByUsername("admin").getPassword();

        for (var bad : List.of(new String[] {"wrong", "new-password-1", "new-password-1", "current password is wrong"},
                new String[] {"correct horse battery", "short", "short", "at least 8"},
                new String[] {"correct horse battery", "new-password-1", "different-1", "different"})) {
            mvc.perform(post("/settings/password").param("current", bad[0]).param("password", bad[1]).param("confirm", bad[2])
                    .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString(bad[3])));
        }
        assertThat(users.loadUserByUsername("admin").getPassword()).isEqualTo(hash);

        mvc.perform(post("/settings/password").param("current", "correct horse battery").param("password", "new-password-1")
                .param("confirm", "new-password-1").with(ADMIN).with(csrf())).andExpect(flash().attribute("message", "Password changed"));
        mvc.perform(post("/login").param("username", "admin").param("password", "new-password-1").with(csrf()))
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("username", "admin").param("password", "correct horse battery").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @Order(16)
    void everyActionIsInTheAuditLog() throws Exception {
        mvc.perform(get("/logs").param("tab", "audit").with(ADMIN)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT action FROM audit_log").query(String.class).list()).contains(
                "endpoint_add", "schema_view", "migration_create", "plan_start", "plan_done", "dry_run_start", "dry_run_done",
                "settings_save", "password_change", "login", "login_failed");
    }

    @Test
    @Order(17)
    void aRunNeedsADryRunThatIsNotBlocked() throws Exception {
        long split = createMigration("not ready", "backup", null, Map.of("orgIds", "2"));
        mvc.perform(post("/migrations/" + split + "/run").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("dry run first")));

        Migration merge = migrations.all().stream().filter(m -> m.name().equals("org 2 back to live")).findFirst().orElseThrow();
        mvc.perform(post("/migrations/" + merge.id() + "/run").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("dry run is blocked")));
        mvc.perform(get("/migrations/" + split).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Run the dry run first")));
        assertThat(migrations.find(split).orElseThrow().state()).isEqualTo(Migration.State.DRAFT);
    }

    @Test
    @Order(18)
    void aSplitIsRunToADumpFileAndTheResultIsShown() throws Exception {
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("split-out-");
        useDirectories(out, java.nio.file.Files.createTempDirectory("backups-"));
        long id = createMigration("Split org 2!", "backup", null, Map.of("orgIds", "2"));
        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        awaitState(id, Migration.State.PLANNED);
        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf()));
        awaitState(id, Migration.State.DRY_RUN_DONE);

        mvc.perform(post("/migrations/" + id + "/run").with(ADMIN).with(csrf())).andExpect(redirectedUrl("/migrations/" + id));
        mvc.perform(post("/migrations/" + id + "/run").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("already working")));
        Migration done = awaitState(id, Migration.State.RUN_DONE, Duration.ofMinutes(8));

        var result = com.etolv.neo4jtool.engine.Json.read(done.runJson(), com.etolv.neo4jtool.engine.build.SplitResult.class);
        assertThat(result.nodes()).isEqualTo(10);
        assertThat(result.relationships()).isEqualTo(1);
        assertThat(java.nio.file.Path.of(result.outputFile())).isRegularFile().hasParent(out)
                .satisfies(p -> assertThat(p.getFileName().toString()).startsWith("split-org-2-" + id + "-").endsWith(".dump"));
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString(result.outputFile())))
                .andExpect(content().string(containsString("Relationship ids in the new database are new")));
        assertThat(jdbc.sql("SELECT message FROM job_logs WHERE scope = ?").param("migration:" + id).query(String.class).list())
                .anyMatch(m -> m.startsWith("Run finished"));
        assertThat(jdbc.sql("SELECT action FROM audit_log").query(String.class).list()).contains("run_start", "run_done");

        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));            // a new plan makes the old result stale
        awaitState(id, Migration.State.PLANNED);
        assertThat(migrations.find(id).orElseThrow().runJson()).isNull();
    }

    @Test
    @Order(19)
    void timesInTheLogsAreTheTimesThingsHappened() {
        jobLog.add("migration:time-check", "INFO", "now");

        java.time.Instant shown = jobLog.forScope("migration:time-check", 1).get(0).at();
        assertThat(java.time.Duration.between(shown, java.time.Instant.now()).abs()).isLessThan(Duration.ofMinutes(2));
    }

    @Test
    @Order(30)
    void aStoppedDataDirectoryIsReadDirectlyAndItsFilesAreLeftAlone() throws Exception {
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("split-dir-out-");
        useDirectories(out, java.nio.file.Files.createTempDirectory("backups-"));
        settings.save(withDirectoryOutput(settings.get()));
        java.nio.file.Path stopped = stoppedCopyOfTheBackupWithoutOrgOne();
        addEndpoint("stopped source", "STOPPED", stopped.toString(), "", "");
        String before = com.etolv.neo4jtool.engine.build.StoreSignature.of(stopped, "neo4j");
        long id = createMigration("split org 2 from a stopped directory", "stopped source", null, Map.of("orgIds", "2"));
        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        awaitState(id, Migration.State.PLANNED, Duration.ofMinutes(5));
        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf()));
        awaitState(id, Migration.State.DRY_RUN_DONE, Duration.ofMinutes(5));
        mvc.perform(post("/migrations/" + id + "/run").with(ADMIN).with(csrf()));
        Migration done = awaitState(id, Migration.State.RUN_DONE, Duration.ofMinutes(8));

        var result = com.etolv.neo4jtool.engine.Json.read(done.runJson(), com.etolv.neo4jtool.engine.build.SplitResult.class);
        assertThat(result.nodes()).isEqualTo(10);
        assertThat(java.nio.file.Path.of(result.outputFile()).resolve("databases").resolve("neo4j").resolve("neostore")).exists();
        assertThat(java.nio.file.Path.of(result.outputFile()).getFileName().toString()).doesNotEndWith(".dump");
        List<String> lines = jdbc.sql("SELECT message FROM job_logs WHERE scope = ?").param("migration:" + id).query(String.class).list();
        assertThat(lines).as("nothing fell back to a temporary server").noneMatch(m -> m.contains("Fast reader not used")
                || m.contains("Fast reader failed") || m.contains("Fast reader off") || m.contains("changed while they were read"));
        assertThat(com.etolv.neo4jtool.engine.build.StoreSignature.of(stopped, "neo4j")).as("the source files").isEqualTo(before);
        useDirectories(out, java.nio.file.Files.createTempDirectory("backups-"));      // back to dump files
    }

    private static AppSettings withDirectoryOutput(AppSettings c) {
        return new AppSettings(c.runner(), c.dockerImage(), c.neo4jHome(), c.workDir(), c.outputDir(), c.backupDir(), c.mergeSwap(),
                c.copySchema(), c.checkAfterBuild(), c.tempHeap(), c.tempPageCache(), c.startTimeoutMinutes(), c.orgProperty(),
                c.includeNeighbours(), c.batchSize(), c.sampleSize(), c.analyseRelationships(), c.fastReader(), c.readerLib(),
                c.readerHeap(), c.readerPageCache(), c.workers(), c.importThreads(), c.importMemory(), c.idSetMemoryMb(),
                c.maxJobs(), true);
    }

    @Test
    @Order(20)
    void aMergeIntoAStoppedDirectoryBacksUpSwapsAndCanBeRolledBack() throws Exception {
        java.nio.file.Path backups = java.nio.file.Files.createTempDirectory("backups-");
        useDirectories(java.nio.file.Files.createTempDirectory("merge-out-"), backups);
        java.nio.file.Path stopped = stoppedCopyOfTheBackupWithoutOrgOne();
        addEndpoint("old server", "STOPPED", stopped.toString(), "", "");
        long id = createMigration("restore org 1 into the stopped server", "backup", "old server", Map.of("orgIds", "1"));
        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        awaitState(id, Migration.State.PLANNED, Duration.ofMinutes(5));
        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf()));
        Migration dry = awaitState(id, Migration.State.DRY_RUN_DONE, Duration.ofMinutes(5));
        assertThat(reports.find(dry.dryRunId()).orElseThrow().missingNodes()).isEqualTo(10);

        mvc.perform(post("/migrations/" + id + "/run").with(ADMIN).with(csrf())).andExpect(redirectedUrl("/migrations/" + id));
        Migration done = awaitState(id, Migration.State.RUN_DONE, Duration.ofMinutes(10));

        var merge = com.etolv.neo4jtool.engine.Json.read(done.runJson(), com.etolv.neo4jtool.engine.build.MergeResult.class);
        assertThat(merge.swapped()).isTrue();
        assertThat(jdbc.sql("SELECT message FROM job_logs WHERE scope = ?").param("migration:" + id).query(String.class).list())
                .as("the backup of the target was read directly").noneMatch(m -> m.contains("reading it over Bolt instead"))
                .anyMatch(m -> m.contains("Loading ") && m.contains("as database"));
        assertThat(merge.restoredNodes()).isEqualTo(10);
        assertThat(merge.targetNodes()).isEqualTo(10);
        assertThat(java.nio.file.Path.of(merge.backupFile())).isRegularFile().hasParent(backups);
        assertThat(stopped.resolve(merge.keepName()).resolve("databases").resolve("neo4j")).isDirectory();
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Backup of the target")))
                .andExpect(content().string(containsString(merge.keepName())))
                .andExpect(content().string(containsString("Roll back")));
        mvc.perform(post("/migrations/" + id + "/run").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("Roll it back first")));

        mvc.perform(post("/migrations/" + id + "/rollback").with(ADMIN).with(csrf())).andExpect(redirectedUrl("/migrations/" + id))
                .andExpect(flash().attribute("message", containsString("Rolled back")));
        var rolledBack = com.etolv.neo4jtool.engine.Json.read(migrations.find(id).orElseThrow().runJson(),
                com.etolv.neo4jtool.engine.build.MergeResult.class);
        assertThat(rolledBack.rolledBackAt()).isNotNull();
        assertThat(stopped.resolve(merge.keepName())).doesNotExist();
        assertThat(stopped.resolve("rolled-back-" + merge.keepName()).resolve("databases").resolve("neo4j")).isDirectory();
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(content().string(containsString("ROLLED BACK")));
        mvc.perform(post("/migrations/" + id + "/rollback").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.anyOf(containsString("no finished merge"), containsString("already rolled back"))));
        assertThat(jdbc.sql("SELECT action FROM audit_log").query(String.class).list()).contains("rollback_start", "rollback_done");
    }

    @Test
    @Order(21)
    void aRunningServerCannotBeMergedInto() throws Exception {
        Migration toLive = migrations.all().stream().filter(m -> m.name().equals("org 1")).findFirst().orElseThrow();
        assertThat(toLive.state()).isEqualTo(Migration.State.DRY_RUN_DONE);

        mvc.perform(post("/migrations/" + toLive.id() + "/run").with(ADMIN).with(csrf()))
                .andExpect(flash().attribute("error", containsString("running server cannot be merged into")));
        mvc.perform(get("/migrations/" + toLive.id()).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Neo4j Community cannot write nodes with chosen ids")));
        assertThat(migrations.find(toLive.id()).orElseThrow().state()).isEqualTo(Migration.State.DRY_RUN_DONE);
    }

    @Test
    @Order(21)
    void aSignInLastsAsLongAsTheSettingsSay() throws Exception {
        AppSettings before = settings.get();
        try {
            assertThat(before.sessionHours()).isEqualTo(12);
            var session = new org.springframework.mock.web.MockHttpSession();
            new SessionLength(settings).sessionCreated(new jakarta.servlet.http.HttpSessionEvent(session));
            assertThat(session.getMaxInactiveInterval()).isEqualTo(12 * 3600);

            mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "10000", Map.of("sessionHours", "30"))
                    .with(ADMIN).with(csrf()));
            assertThat(settings.get().sessionHours()).isEqualTo(30);
            var later = new org.springframework.mock.web.MockHttpSession();
            new SessionLength(settings).sessionCreated(new jakarta.servlet.http.HttpSessionEvent(later));
            assertThat(later.getMaxInactiveInterval()).isEqualTo(30 * 3600);

            mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "10000", Map.of("sessionHours", "0"))
                    .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString("between 1 and 720")));
            assertThat(settings.get().sessionHours()).isEqualTo(30);
        } finally {
            settings.save(before);
        }
    }

    @Test
    @Order(23)
    void theLiveFeedTellsWhereAJobIsAndGivesTheLinesOfTheLatestRunOnly() throws Exception {
        long id = createMigration("live feed", "backup", "live", Map.of("orgIds", "1"));
        String scope = "migration:" + id;
        jobLog.add(scope, "INFO", "Dry run started");
        jobLog.add(scope, "ERROR", "Dry run failed: an old problem");
        jobLog.add(scope, "INFO", "Run started");
        jobLog.add(scope, "STEP", "Backing up the target");
        jobLog.add(scope, "INFO", "Backup: /somewhere/target.dump");

        // the page shows the latest run only, the Logs page has the rest
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("an old problem"))))
                .andExpect(content().string(containsString("Backing up the target")))
                .andExpect(content().string(containsString("class=\"STEP imp\"")));

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(mvc.perform(get("/live.json").param("migration", String.valueOf(id)).with(ADMIN))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString());
        assertThat(first.at("/job/busy").asBoolean()).isFalse();
        assertThat(first.at("/job/log")).hasSize(3);
        assertThat(first.at("/job/log/0/text").asText()).isEqualTo("Run started");
        assertThat(first.at("/job/log/1/cls").asText()).isEqualTo("STEP");
        assertThat(first.at("/job/steps/run").size()).isGreaterThan(8);
        assertThat(first.at("/job/steps/run/0/state").asText()).isEqualTo("WAITING");
        assertThat(first.at("/job/steps/plan/0/title").asText()).isEqualTo("Open the source");
        assertThat(first.at("/interval_seconds").asInt()).isEqualTo(2);
        assertThat(first.at("/resources/disk_total").asLong()).isPositive();

        // only what came after the last line the page has
        long last = first.at("/job/last_log_id").asLong();
        jobLog.add(scope, "INFO", "something later");
        var next = mapper.readTree(mvc.perform(get("/live.json").param("migration", String.valueOf(id)).param("after", String.valueOf(last))
                .with(ADMIN)).andReturn().getResponse().getContentAsString());
        assertThat(next.at("/job/log")).hasSize(1);
        assertThat(next.at("/job/log/0/text").asText()).isEqualTo("something later");
        assertThat(next.at("/job/log_from").asLong()).isEqualTo(first.at("/job/log_from").asLong());

        // a new run: the page is told to start its log again
        jobLog.add(scope, "INFO", "Run started");
        var again = mapper.readTree(mvc.perform(get("/live.json").param("migration", String.valueOf(id)).param("after", String.valueOf(last))
                .with(ADMIN)).andReturn().getResponse().getContentAsString());
        assertThat(again.at("/job/log_from").asLong()).isGreaterThan(first.at("/job/log_from").asLong());
        assertThat(again.at("/job/log/0/text").asText()).isEqualTo("Run started");

        // the resources alone, for the pages that show no job
        var alone = mapper.readTree(mvc.perform(get("/live.json").with(ADMIN)).andReturn().getResponse().getContentAsString());
        assertThat(alone.at("/job").isNull()).isTrue();
        assertThat(alone.at("/resources").isObject()).isTrue();

        // nobody who is not signed in sees any of it
        mvc.perform(get("/live.json")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/live/stream")).andExpect(status().is3xxRedirection());
    }

    @Test
    @Order(23)
    void theStreamPushesTheSameSnapshot() throws Exception {
        long id = createMigration("live stream", "backup", "live", Map.of("orgIds", "1"));
        var result = mvc.perform(get("/live/stream").param("migration", String.valueOf(id)).with(ADMIN))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        String body = "";
        for (int i = 0; i < 40 && !body.contains("data:"); i++) {
            Thread.sleep(100);
            body = result.getResponse().getContentAsString();
        }
        assertThat(body).contains("event:live").contains("\"steps\"").contains("\"resources\"");
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    @Order(24)
    void theLiveRefreshIsASettingWithinLimits() throws Exception {
        AppSettings before = settings.get();
        try {
            assertThat(before.liveSeconds()).isEqualTo(2);
            mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "10000", Map.of("liveSeconds", "5"))
                    .with(ADMIN).with(csrf()));
            assertThat(settings.get().liveSeconds()).isEqualTo(5);
            mvc.perform(settingsForm("DOCKER", "neo4j:5.26.29-community", "org_id", "10000", Map.of("liveSeconds", "0"))
                    .with(ADMIN).with(csrf())).andExpect(flash().attribute("error", containsString("between 1 and 60")));
            assertThat(settings.get().liveSeconds()).isEqualTo(5);
            mvc.perform(get("/settings").with(ADMIN)).andExpect(content().string(containsString("Live refresh (seconds)")));
        } finally {
            settings.save(before);
        }
    }

    @Test
    @Order(22)
    void linesWithoutAnOrganisationComeWithTheirParentAndWhatIsLeftOutIsListed() throws Exception {
        // A line that only belongs to a node of company 1 (no organisation of its own), and a link from company 1 to company 2.
        run(BACKUP, "MATCH (a) WHERE id(a) = 0 CREATE (:Line {n: 1})-[:PART_OF]->(a)");
        run(BACKUP, "MATCH (a) WHERE id(a) = 0 MATCH (b) WHERE id(b) = 12 CREATE (a)-[:SEES]->(b)");
        long id = createMigration("org 1 and its lines", "backup", null, Map.of("orgIds", "1", "includeNeighbours", "true"));
        assertThat(migrations.find(id).orElseThrow().selectionYaml()).contains("include_neighbours: true");

        mvc.perform(post("/migrations/" + id + "/plan").with(ADMIN).with(csrf()));
        Migration planned = awaitState(id, Migration.State.PLANNED);
        MigrationPlan plan = com.etolv.neo4jtool.engine.Json.read(planned.planJson(), MigrationPlan.class);
        assertThat(plan.selectedNodes()).isEqualTo(11);
        assertThat(plan.neighbourNodes()).isEqualTo(1);
        assertThat(plan.neighbourLabels()).containsExactly(new MigrationPlan.LabelCount("Line", 1));
        mvc.perform(get("/migrations/" + id).with(ADMIN)).andExpect(content().string(containsString("come along because they are")));

        mvc.perform(post("/migrations/" + id + "/dry-run").with(ADMIN).with(csrf()));
        Migration done = awaitState(id, Migration.State.DRY_RUN_DONE);
        mvc.perform(get("/dry-runs/" + done.dryRunId()).with(ADMIN)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Relationships that are not moved")))
                .andExpect(content().string(containsString("The other node belongs to another organisation")))
                .andExpect(content().string(containsString("<span> (2)</span>")))
                .andExpect(content().string(containsString("SEES")));

        long without = createMigration("org 1 only", "backup", null, Map.of("orgIds", "1"));
        mvc.perform(post("/migrations/" + without + "/plan").with(ADMIN).with(csrf()));
        MigrationPlan plain = com.etolv.neo4jtool.engine.Json.read(awaitState(without, Migration.State.PLANNED).planJson(), MigrationPlan.class);
        assertThat(plain.selectedNodes()).isEqualTo(10);
        assertThat(plain.neighbourNodes()).isZero();
    }

    // ---- helpers ----

    private void useDirectories(java.nio.file.Path output, java.nio.file.Path backups) {
        AppSettings c = settings.get();
        settings.save(new AppSettings(c.runner(), c.dockerImage(), c.neo4jHome(), c.workDir(), output.toString(), backups.toString(),
                true, true, false, c.tempHeap(), c.tempPageCache(), c.startTimeoutMinutes(), c.orgProperty(), c.includeNeighbours(), c.batchSize(),
                c.sampleSize(), c.analyseRelationships()));
    }

    /** The data directory of a stopped server that holds what "backup" holds, except the nodes of organisation 1. */
    private static java.nio.file.Path stoppedCopyOfTheBackupWithoutOrgOne() throws Exception {
        java.nio.file.Path data = java.nio.file.Files.createTempDirectory("stopped-");
        java.nio.file.Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        STOPPED_DIRECTORIES.add(data);
        String name = "web-it-stopped-" + System.nanoTime();
        docker("run", "-d", "--name", name, "-e", "NEO4J_AUTH=none", "-e", "NEO4J_server_memory_heap_max__size=512m",
                "-e", "NEO4J_server_memory_pagecache_size=128m", "-p", "127.0.0.1::7687", "-v", data.toAbsolutePath() + ":/data", NEO4J_IMAGE);
        try {
            String port = docker("port", name, "7687/tcp").lines().findFirst().orElseThrow();
            String uri = "bolt://127.0.0.1:" + port.substring(port.lastIndexOf(':') + 1);
            try (Driver driver = GraphDatabase.driver(uri, AuthTokens.none())) {
                long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
                while (true) {
                    try (var session = driver.session()) {
                        session.run("RETURN 1").consume();
                        break;
                    } catch (RuntimeException e) {
                        if (System.nanoTime() > deadline) {
                            throw e;
                        }
                        Thread.sleep(1000);
                    }
                }
                for (String statement : List.of("UNWIND range(1, 10) AS i CREATE (:Service {org_id: 1})",
                        "UNWIND range(1, 10) AS i CREATE (:Service {org_id: 2})",
                        "MATCH (a) WHERE id(a) = 10 MATCH (b) WHERE id(b) = 11 CREATE (a)-[:NEXT]->(b)",
                        "MATCH (n) WHERE n.org_id = 1 DETACH DELETE n")) {
                    try (var session = driver.session()) {
                        session.run(statement).consume();
                    }
                }
            }
        } finally {
            docker("stop", "-t", "120", name);
            docker("rm", name);
        }
        return data;
    }

    private static final List<java.nio.file.Path> STOPPED_DIRECTORIES = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.AfterAll
    static void removeStoppedDirectories() {
        // Files written by a container belong to another user, so a container removes them.
        for (java.nio.file.Path dir : STOPPED_DIRECTORIES) {
            docker("run", "--rm", "--entrypoint", "sh", "-v", dir.getParent().toAbsolutePath() + ":/w", NEO4J_IMAGE, "-c",
                    "rm -rf /w/" + dir.getFileName());
        }
    }

    private static String docker(String... args) throws RuntimeException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return output;
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }



    private void addEndpoint(String name, String kind, String location, String user, String password) throws Exception {
        mvc.perform(post("/endpoints").param("name", name).param("kind", kind).param("location", location)
                .param("username", user).param("password", password).with(ADMIN).with(csrf()))
                .andExpect(redirectedUrl("/endpoints"));
    }

    private long endpointId(String name) {
        return jdbc.sql("SELECT id FROM endpoints WHERE name = ?").param(name).query(Long.class).single();
    }

    private long createMigration(String name, String source, String target, Map<String, String> selection) throws Exception {
        var request = post("/migrations").param("name", name).param("source", String.valueOf(endpointId(source))).with(ADMIN).with(csrf());
        if (target != null) {
            request = request.param("target", String.valueOf(endpointId(target)));
        }
        for (var e : selection.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        String location = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsForm(
            String runner, String image, String orgProperty, String batchSize) {
        return settingsForm(runner, image, orgProperty, batchSize, Map.of());
    }

    /** The settings form; {@code changes} replaces the value the form would otherwise send for a field. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsForm(
            String runner, String image, String orgProperty, String batchSize, Map<String, String> changes) {
        Map<String, String> speed = new java.util.HashMap<>(Map.of("fastReader", "true", "readerHeap", "1g", "readerPageCache", "256m",
                "workers", "3", "importThreads", "2", "importMemory", "", "idSetMemoryMb", "64", "maxJobs", "2"));
        speed.putAll(changes);
        var request = post("/settings").param("runner", runner).param("dockerImage", image).param("neo4jHome", "")
                .param("workDir", System.getProperty("java.io.tmpdir")).param("outputDir", System.getProperty("java.io.tmpdir") + "/out").param("backupDir", System.getProperty("java.io.tmpdir") + "/backups").param("tempHeap", "512m").param("tempPageCache", "128m")
                .param("startTimeoutMinutes", "10").param("orgProperty", orgProperty).param("batchSize", batchSize)
                .param("sampleSize", "20").param("analyseRelationships", "true");
        speed.forEach(request::param);
        return request;
    }

    private Migration awaitState(long id, Migration.State wanted) throws InterruptedException {
        return awaitState(id, wanted, Duration.ofSeconds(90));
    }

    private Migration awaitState(long id, Migration.State wanted, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Migration m = migrations.find(id).orElseThrow();
            if (m.state() == wanted) {
                return m;
            }
            if (!m.state().busy() && m.lastError() != null) {
                throw new AssertionError("Migration " + id + " failed: " + m.lastError());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Migration " + id + " did not reach " + wanted + " in " + timeout.toSeconds() + " s");
    }
}
