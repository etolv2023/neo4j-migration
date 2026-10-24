package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.Json;
import com.etolv.neo4jtool.engine.dryrun.DryRunOptions;
import com.etolv.neo4jtool.engine.dryrun.DryRunReport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Dry-run history. The full report is kept as JSON; the columns are what the list page shows. */
@Repository
public class ReportStore {

    public enum Status { RUNNING, DONE, FAILED }

    public record Row(long id, Long migrationId, String sourceName, String targetName, String selectionYaml, Status status, String gate,
                      Long selectedNodes, Long missingNodes, Long sameNodes, Long conflictNodes, String error,
                      Instant startedAt, Instant finishedAt) {
    }

    private final JdbcClient jdbc;

    public ReportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long begin(long migrationId, String sourceName, String targetName, String selectionYaml, DryRunOptions options) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dry_run_reports (migration_id, source_name, target_name, selection_yaml, options_json, status) "
                + "VALUES (?, ?, ?, ?, ?, 'RUNNING')")
                .params(migrationId, sourceName, targetName, selectionYaml, Json.write(options)).update(keys);
        return keys.getKey().longValue();
    }

    public void finish(long id, DryRunReport report) {
        var nodes = report.nodes();
        jdbc.sql("UPDATE dry_run_reports SET status = 'DONE', gate = ?, selected_nodes = ?, missing_nodes = ?, "
                + "same_nodes = ?, conflict_nodes = ?, report_json = ?, finished_at = CURRENT_TIMESTAMP(3) WHERE id = ?")
                .params(report.gate().name(), report.selectedNodes(), nodes == null ? null : nodes.missing(),
                        nodes == null ? null : nodes.same(), nodes == null ? null : nodes.conflict(), Json.write(report), id)
                .update();
    }

    public void fail(long id, String error) {
        jdbc.sql("UPDATE dry_run_reports SET status = 'FAILED', error = ?, finished_at = CURRENT_TIMESTAMP(3) WHERE id = ?")
                .params(error, id).update();
    }

    /** A run cannot survive a restart of the dashboard, so anything still marked running is over. */
    public int failInterrupted() {
        return jdbc.sql("UPDATE dry_run_reports SET status = 'FAILED', error = 'Interrupted by a restart of the dashboard', "
                + "finished_at = CURRENT_TIMESTAMP(3) WHERE status = 'RUNNING'").update();
    }

    public List<Row> latest(int limit) {
        return jdbc.sql("SELECT id, migration_id, source_name, target_name, selection_yaml, status, gate, selected_nodes, missing_nodes, "
                + "same_nodes, conflict_nodes, error, started_at, finished_at FROM dry_run_reports ORDER BY id DESC LIMIT ?")
                .param(limit).query(ReportStore::map).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("SELECT id, migration_id, source_name, target_name, selection_yaml, status, gate, selected_nodes, missing_nodes, "
                + "same_nodes, conflict_nodes, error, started_at, finished_at FROM dry_run_reports WHERE id = ?")
                .param(id).query(ReportStore::map).optional();
    }

    public Optional<String> reportJson(long id) {
        return jdbc.sql("SELECT report_json FROM dry_run_reports WHERE id = ? AND report_json IS NOT NULL")
                .param(id).query(String.class).optional();
    }

    public void saveMapping(long reportId, long entries, byte[] data) {
        jdbc.sql("REPLACE INTO dry_run_mappings (report_id, entries, data) VALUES (?, ?, ?)").params(reportId, entries, data).update();
    }

    /** How many rows the stored table of new ids has; empty when none was stored. */
    public Optional<Long> mappingEntries(long reportId) {
        return jdbc.sql("SELECT entries FROM dry_run_mappings WHERE report_id = ?").param(reportId).query(Long.class).optional();
    }

    public Optional<byte[]> mapping(long reportId) {
        return jdbc.sql("SELECT data FROM dry_run_mappings WHERE report_id = ?").param(reportId).query(byte[].class).optional();
    }

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long migrationId = rs.getLong("migration_id");
        Long migration = rs.wasNull() ? null : migrationId;
        return new Row(rs.getLong("id"), migration, rs.getString("source_name"), rs.getString("target_name"),
                rs.getString("selection_yaml"), Status.valueOf(rs.getString("status")), rs.getString("gate"),
                nullableLong(rs, "selected_nodes"), nullableLong(rs, "missing_nodes"), nullableLong(rs, "same_nodes"),
                nullableLong(rs, "conflict_nodes"), rs.getString("error"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
