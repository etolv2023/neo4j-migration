package com.etolv.neo4jtool.web;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

/** Append-only record of what people did in the dashboard. */
@Repository
public class AuditLog {

    public record Entry(long id, Instant at, String actor, String action, String detail) {
    }

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        record(auth == null ? "system" : auth.getName(), action, detail);
    }

    public void record(String actor, String action, String detail) {
        String clipped = detail != null && detail.length() > 2000 ? detail.substring(0, 2000) : detail;
        jdbc.sql("INSERT INTO audit_log (actor, action, detail) VALUES (?, ?, ?)")
                .params(actor, action, clipped).update();
    }

    public List<Entry> latest(int limit) {
        return jdbc.sql("SELECT id, at, actor, action, detail FROM audit_log ORDER BY id DESC LIMIT ?")
                .param(limit)
                .query(AuditLog::map)
                .list();
    }

    /** Only sign-ins, failed sign-ins and sign-outs. */
    public List<Entry> latestLogins(int limit) {
        return jdbc.sql("SELECT id, at, actor, action, detail FROM audit_log WHERE action IN ('login', 'login_failed', 'logout') "
                        + "ORDER BY id DESC LIMIT ?")
                .param(limit)
                .query(AuditLog::map)
                .list();
    }

    private static Entry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Entry(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("actor"),
                rs.getString("action"), rs.getString("detail"));
    }
}
