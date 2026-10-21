package com.etolv.neo4jtool.web;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MigrationStore {

    private final JdbcClient jdbc;

    public MigrationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String name, Endpoint source, Endpoint targetOrNull, String selectionYaml) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO migrations (name, source_id, target_id, source_name, target_name, selection_yaml, state) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'DRAFT')")
                .params(name, source.id(), targetOrNull == null ? null : targetOrNull.id(), source.name(),
                        targetOrNull == null ? null : targetOrNull.name(), selectionYaml)
                .update(keys);
        return keys.getKey().longValue();
    }

    public List<Migration> all() {
        return jdbc.sql("SELECT * FROM migrations ORDER BY id DESC").query(MigrationStore::map).list();
    }

    public Optional<Migration> find(long id) {
        return jdbc.sql("SELECT * FROM migrations WHERE id = ?").param(id).query(MigrationStore::map).optional();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM migrations WHERE id = ?").param(id).update();
    }

    public void start(long id, Migration.State busyState) {
        jdbc.sql("UPDATE migrations SET state = ?, last_error = NULL WHERE id = ?").params(busyState.name(), id).update();
    }

    public void planned(long id, String planJson) {
        jdbc.sql("UPDATE migrations SET state = 'PLANNED', plan_json = ?, dry_run_id = NULL, run_json = NULL, last_error = NULL WHERE id = ?")
                .params(planJson, id).update();
    }

    public void dryRunDone(long id, long dryRunId) {
        jdbc.sql("UPDATE migrations SET state = 'DRY_RUN_DONE', dry_run_id = ?, run_json = NULL, last_error = NULL WHERE id = ?")
                .params(dryRunId, id).update();
    }

    public void ran(long id, String runJson) {
        jdbc.sql("UPDATE migrations SET state = 'RUN_DONE', run_json = ?, last_error = NULL WHERE id = ?").params(runJson, id).update();
    }

    /** A job failed: go back to the state before it and keep the reason. */
    public void failed(long id, Migration.State backTo, String error) {
        jdbc.sql("UPDATE migrations SET state = ?, last_error = ? WHERE id = ?").params(backTo.name(), error, id).update();
    }

    /** Jobs do not survive a restart of the dashboard. */
    public int failInterrupted() {
        String reason = "Interrupted by a restart of the dashboard";
        int planning = jdbc.sql("UPDATE migrations SET state = 'DRAFT', last_error = ? WHERE state = 'PLANNING'")
                .param(reason).update();
        int dryRunning = jdbc.sql("UPDATE migrations SET state = 'PLANNED', last_error = ? WHERE state = 'DRY_RUNNING'")
                .param(reason).update();
        int running = jdbc.sql("UPDATE migrations SET state = 'DRY_RUN_DONE', last_error = ? WHERE state = 'RUNNING'")
                .param(reason).update();
        return planning + dryRunning + running;
    }

    private static Migration map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long targetId = rs.getLong("target_id");
        Long target = rs.wasNull() ? null : targetId;
        long dryRunId = rs.getLong("dry_run_id");
        Long dryRun = rs.wasNull() ? null : dryRunId;
        return new Migration(rs.getLong("id"), rs.getString("name"), rs.getLong("source_id"), target,
                rs.getString("source_name"), rs.getString("target_name"), rs.getString("selection_yaml"),
                Migration.State.valueOf(rs.getString("state")), rs.getString("plan_json"), dryRun, rs.getString("run_json"), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
