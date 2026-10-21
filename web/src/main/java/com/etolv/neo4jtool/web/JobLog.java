package com.etolv.neo4jtool.web;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What background jobs print while they work, kept so it can be read afterwards. */
@Repository
public class JobLog {

    public record Line(long id, String scope, Instant at, String level, String message) {
    }

    // neo4j-admin prints a line per file while loading a dump; only the finished one is worth keeping.
    private static final java.util.regex.Pattern PROGRESS =
            java.util.regex.Pattern.compile("^(\\S+ \\S+ )?Files: \\d+/\\d+, data:\\s+(?!\\s*100\\.0%)");

    private final JdbcClient jdbc;

    public JobLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(String scope, String level, String message) {
        String clipped = message.length() > 2000 ? message.substring(0, 2000) : message;
        jdbc.sql("INSERT INTO job_logs (scope, level, message) VALUES (?, ?, ?)").params(scope, level, clipped).update();
    }

    static boolean isProgress(String line) {
        return PROGRESS.matcher(line).find();
    }

    /** A sink for the output of commands and servers started by a job. */
    public Consumer<String> sink(String scope) {
        return line -> {
            if (!isProgress(line)) {
                add(scope, "INFO", line);
            }
        };
    }

    /** Oldest first, so it reads as it happened. */
    public List<Line> forScope(String scope, int limit) {
        List<Line> latest = jdbc.sql("SELECT * FROM job_logs WHERE scope = ? ORDER BY id DESC LIMIT ?")
                .params(scope, limit).query(JobLog::map).list();
        return latest.reversed();
    }

    /** Newest first. */
    public List<Line> latest(int limit) {
        return jdbc.sql("SELECT * FROM job_logs ORDER BY id DESC LIMIT ?").param(limit).query(JobLog::map).list();
    }

    public void deleteScope(String scope) {
        jdbc.sql("DELETE FROM job_logs WHERE scope = ?").param(scope).update();
    }

    private static Line map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Line(rs.getLong("id"), rs.getString("scope"), rs.getTimestamp("at").toInstant(), rs.getString("level"),
                rs.getString("message"));
    }
}
