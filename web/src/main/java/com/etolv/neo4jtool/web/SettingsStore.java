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
                rows.getOrDefault("output_dir", d.outputDir()),
                rows.getOrDefault("backup_dir", d.backupDir()),
                Boolean.parseBoolean(rows.getOrDefault("merge_swap", String.valueOf(d.mergeSwap()))),
                Boolean.parseBoolean(rows.getOrDefault("copy_schema", String.valueOf(d.copySchema()))),
                Boolean.parseBoolean(rows.getOrDefault("check_after_build", String.valueOf(d.checkAfterBuild()))),
                rows.getOrDefault("temp_heap", d.tempHeap()),
                rows.getOrDefault("temp_page_cache", d.tempPageCache()),
                Integer.parseInt(rows.getOrDefault("start_timeout_minutes", String.valueOf(d.startTimeoutMinutes()))),
                rows.getOrDefault("org_property", d.orgProperty()),
                Boolean.parseBoolean(rows.getOrDefault("include_neighbours", String.valueOf(d.includeNeighbours()))),
                Integer.parseInt(rows.getOrDefault("batch_size", String.valueOf(d.batchSize()))),
                Integer.parseInt(rows.getOrDefault("sample_size", String.valueOf(d.sampleSize()))),
                Boolean.parseBoolean(rows.getOrDefault("analyse_relationships", String.valueOf(d.analyseRelationships()))),
                Boolean.parseBoolean(rows.getOrDefault("fast_reader", String.valueOf(d.fastReader()))),
                rows.getOrDefault("reader_lib", d.readerLib()),
                rows.getOrDefault("reader_heap", d.readerHeap()),
                rows.getOrDefault("reader_page_cache", d.readerPageCache()),
                Integer.parseInt(rows.getOrDefault("workers", String.valueOf(d.workers()))),
                Integer.parseInt(rows.getOrDefault("import_threads", String.valueOf(d.importThreads()))),
                rows.getOrDefault("import_memory", d.importMemory()),
                Integer.parseInt(rows.getOrDefault("id_set_memory_mb", String.valueOf(d.idSetMemoryMb()))),
                Integer.parseInt(rows.getOrDefault("max_jobs", String.valueOf(d.maxJobs()))),
                Boolean.parseBoolean(rows.getOrDefault("split_as_directory", String.valueOf(d.splitAsDirectory()))),
                Boolean.parseBoolean(rows.getOrDefault("require_two_factor", String.valueOf(d.requireTwoFactor()))),
                Integer.parseInt(rows.getOrDefault("session_hours", String.valueOf(d.sessionHours()))),
                Integer.parseInt(rows.getOrDefault("live_seconds", String.valueOf(d.liveSeconds()))),
                Integer.parseInt(rows.getOrDefault("layout_columns", String.valueOf(d.layoutColumns()))));
    }

    /** @throws IllegalArgumentException when a value cannot work */
    public void save(AppSettings s) {
        validate(s);
        put("runner", s.runner().name());
        put("docker_image", s.dockerImage());
        put("neo4j_home", s.neo4jHome() == null ? "" : s.neo4jHome());
        put("work_dir", s.workDir());
        put("output_dir", s.outputDir());
        put("backup_dir", s.backupDir());
        put("merge_swap", String.valueOf(s.mergeSwap()));
        put("copy_schema", String.valueOf(s.copySchema()));
        put("check_after_build", String.valueOf(s.checkAfterBuild()));
        put("temp_heap", s.tempHeap());
        put("temp_page_cache", s.tempPageCache());
        put("start_timeout_minutes", String.valueOf(s.startTimeoutMinutes()));
        put("org_property", s.orgProperty());
        put("include_neighbours", String.valueOf(s.includeNeighbours()));
        put("batch_size", String.valueOf(s.batchSize()));
        put("sample_size", String.valueOf(s.sampleSize()));
        put("analyse_relationships", String.valueOf(s.analyseRelationships()));
        put("fast_reader", String.valueOf(s.fastReader()));
        put("reader_lib", s.readerLib() == null ? "" : s.readerLib());
        put("reader_heap", s.readerHeap());
        put("reader_page_cache", s.readerPageCache());
        put("workers", String.valueOf(s.workers()));
        put("import_threads", String.valueOf(s.importThreads()));
        put("import_memory", s.importMemory() == null ? "" : s.importMemory());
        put("id_set_memory_mb", String.valueOf(s.idSetMemoryMb()));
        put("max_jobs", String.valueOf(s.maxJobs()));
        put("split_as_directory", String.valueOf(s.splitAsDirectory()));
        put("require_two_factor", String.valueOf(s.requireTwoFactor()));
        put("session_hours", String.valueOf(s.sessionHours()));
        put("live_seconds", String.valueOf(s.liveSeconds()));
        put("layout_columns", String.valueOf(s.layoutColumns()));
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
        if (s.outputDir() == null || s.outputDir().isBlank()) {
            throw new IllegalArgumentException("Give a directory for the dump files a split writes");
        }
        if (s.backupDir() == null || s.backupDir().isBlank()) {
            throw new IllegalArgumentException("Give a directory for the backups a merge takes");
        }
        if (s.orgProperty() == null || s.orgProperty().isBlank()) {
            throw new IllegalArgumentException("Give the property that identifies the organisation");
        }
        if (s.batchSize() < 100) {
            throw new IllegalArgumentException("Ids per query must be at least 100");
        }
        if (s.workers() < 1 || s.workers() > 256) {
            throw new IllegalArgumentException("Reading threads must be between 1 and 256");
        }
        if (s.importThreads() < 0 || s.importThreads() > 1024) {
            throw new IllegalArgumentException("Import threads must be between 0 and 1024");
        }
        if (s.sessionHours() < 1 || s.sessionHours() > 720) {
            throw new IllegalArgumentException("A sign-in must last between 1 and 720 hours");
        }
        if (s.liveSeconds() < 1 || s.liveSeconds() > 60) {
            throw new IllegalArgumentException("The live refresh must be between 1 and 60 seconds");
        }
        if (s.layoutColumns() < 0 || s.layoutColumns() > 10_000) {
            throw new IllegalArgumentException("Shared layout width must be 0 (off) or at most 10000 columns");
        }
        if (s.idSetMemoryMb() < 16) {
            throw new IllegalArgumentException("Memory for the selected ids must be at least 16 MB");
        }
        if (s.maxJobs() < 1 || s.maxJobs() > 64) {
            throw new IllegalArgumentException("Migrations at the same time must be between 1 and 64");
        }
        if (s.fastReader() && (blank(s.readerHeap()) || blank(s.readerPageCache()))) {
            throw new IllegalArgumentException("Give the heap and the page cache of the fast reader, for example 2g");
        }
        if (s.sampleSize() < 0 || s.startTimeoutMinutes() < 1) {
            throw new IllegalArgumentException("Examples must not be negative and the start timeout must be at least 1 minute");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void put(String name, String value) {
        jdbc.sql("INSERT INTO settings (name, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)")
                .params(name, value).update();
    }
}
