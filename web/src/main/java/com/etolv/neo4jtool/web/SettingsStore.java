package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.instance.InstanceSettings;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Settings are rows of name and value; a name without a row has its default. */
@Repository
public class SettingsStore {

    private final JdbcClient jdbc;

    public SettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AppSettings get() {
        Map<String, String> rows = new HashMap<>();
        jdbc.sql("SELECT name, value FROM settings").query((rs, n) -> rows.put(rs.getString("name"), rs.getString("value"))).list();
        AppSettings d = AppSettings.defaults();
        return new AppSettings(
                InstanceSettings.Runner.valueOf(rows.getOrDefault("runner", d.runner().name())),
                rows.getOrDefault("docker_image", d.dockerImage()),
                rows.getOrDefault("neo4j_home", d.neo4jHome()),
                rows.getOrDefault("work_dir", d.workDir()),
                rows.getOrDefault("temp_heap", d.tempHeap()),
                rows.getOrDefault("temp_page_cache", d.tempPageCache()),
                Integer.parseInt(rows.getOrDefault("start_timeout_minutes", String.valueOf(d.startTimeoutMinutes()))),
                rows.getOrDefault("org_property", d.orgProperty()),
                Integer.parseInt(rows.getOrDefault("batch_size", String.valueOf(d.batchSize()))),
                Integer.parseInt(rows.getOrDefault("sample_size", String.valueOf(d.sampleSize()))),
                Boolean.parseBoolean(rows.getOrDefault("analyse_relationships", String.valueOf(d.analyseRelationships()))));
    }

    /** @throws IllegalArgumentException when a value cannot work */
    public void save(AppSettings s) {
        validate(s);
        put("runner", s.runner().name());
        put("docker_image", s.dockerImage());
        put("neo4j_home", s.neo4jHome() == null ? "" : s.neo4jHome());
        put("work_dir", s.workDir());
        put("temp_heap", s.tempHeap());
        put("temp_page_cache", s.tempPageCache());
        put("start_timeout_minutes", String.valueOf(s.startTimeoutMinutes()));
        put("org_property", s.orgProperty());
        put("batch_size", String.valueOf(s.batchSize()));
        put("sample_size", String.valueOf(s.sampleSize()));
        put("analyse_relationships", String.valueOf(s.analyseRelationships()));
    }

    private static void validate(AppSettings s) {
        if (s.runner() == InstanceSettings.Runner.DOCKER && (s.dockerImage() == null || s.dockerImage().isBlank())) {
            throw new IllegalArgumentException("Give the Neo4j image to run");
        }
        if (s.runner() == InstanceSettings.Runner.LOCAL && (s.neo4jHome() == null || s.neo4jHome().isBlank())) {
            throw new IllegalArgumentException("Give the directory of the Neo4j installation");
        }
        if (s.workDir() == null || s.workDir().isBlank()) {
            throw new IllegalArgumentException("Give a work directory");
        }
        if (s.orgProperty() == null || s.orgProperty().isBlank()) {
            throw new IllegalArgumentException("Give the property that identifies the organisation");
        }
        if (s.batchSize() < 100) {
            throw new IllegalArgumentException("Ids per query must be at least 100");
        }
        if (s.sampleSize() < 0 || s.startTimeoutMinutes() < 1) {
            throw new IllegalArgumentException("Examples must not be negative and the start timeout must be at least 1 minute");
        }
    }

    private void put(String name, String value) {
        jdbc.sql("INSERT INTO settings (name, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)")
                .params(name, value).update();
    }
}
