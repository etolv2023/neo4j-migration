package com.etolv.neo4jtool.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;

/** Dashboard users. Passwords are kept as BCrypt hashes; the two-factor secret is kept encrypted. */
@Repository
public class UserStore implements UserDetailsService {

    /** A user as the users page shows it. */
    public record Row(long id, String username, boolean enabled, boolean twoFactor, Instant createdAt) {
    }

    /** What the second sign-in step needs. */
    public record TwoFactor(boolean enabled, String secretEncrypted, long lastStep) {
    }

    public record RecoveryCode(long id, String hash) {
    }

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("SELECT username, password_hash, enabled FROM users WHERE username = ?").param(username)
                .query((rs, n) -> User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                        .disabled(!rs.getBoolean("enabled")).roles("ADMIN").build())
                .optional().orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }

    public boolean isEmpty() {
        return jdbc.sql("SELECT count(*) FROM users").query(Long.class).single() == 0;
    }

    public void create(String username, String passwordHash) {
        jdbc.sql("INSERT INTO users (username, password_hash) VALUES (?, ?)").params(username, passwordHash).update();
    }

    public void changePassword(String username, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = ? WHERE username = ?").params(passwordHash, username).update();
    }

    public List<Row> list() {
        return jdbc.sql("SELECT id, username, enabled, totp_enabled, created_at FROM users ORDER BY username")
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("username"), rs.getBoolean("enabled"),
                        rs.getBoolean("totp_enabled"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Optional<Row> find(long id) {
        return list().stream().filter(r -> r.id() == id).findFirst();
    }

    public boolean exists(String username) {
        return jdbc.sql("SELECT count(*) FROM users WHERE username = ?").param(username).query(Long.class).single() > 0;
    }

    /** True while the user exists and is not switched off. */
    public boolean isEnabled(String username) {
        return jdbc.sql("SELECT count(*) FROM users WHERE username = ? AND enabled = TRUE").param(username)
                .query(Long.class).single() > 0;
    }

    public long countEnabled() {
        return jdbc.sql("SELECT count(*) FROM users WHERE enabled = TRUE").query(Long.class).single();
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.sql("UPDATE users SET enabled = ? WHERE id = ?").params(enabled, id).update();
    }

    public void setPassword(long id, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }

    // ---- two-factor ----

    public TwoFactor twoFactor(String username) {
        return jdbc.sql("SELECT totp_enabled, totp_secret_enc, totp_last_step FROM users WHERE username = ?").param(username)
                .query((rs, n) -> new TwoFactor(rs.getBoolean("totp_enabled"), rs.getString("totp_secret_enc"), rs.getLong("totp_last_step")))
                .optional().orElse(new TwoFactor(false, null, 0));
    }

    public boolean hasTwoFactor(String username) {
        return twoFactor(username).enabled();
    }

    public void enableTwoFactor(String username, String secretEncrypted, long lastStep) {
        jdbc.sql("UPDATE users SET totp_enabled = TRUE, totp_secret_enc = ?, totp_last_step = ? WHERE username = ?")
                .params(secretEncrypted, lastStep, username).update();
    }

    /** Turns the second factor off for a user and drops the recovery codes. */
    public void disableTwoFactor(long id) {
        jdbc.sql("UPDATE users SET totp_enabled = FALSE, totp_secret_enc = NULL, totp_last_step = 0 WHERE id = ?").param(id).update();
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = ?").param(id).update();
    }

    public void rememberStep(String username, long step) {
        jdbc.sql("UPDATE users SET totp_last_step = ? WHERE username = ?").params(step, username).update();
    }

    /** Replaces all recovery codes of a user by new ones (only hashes are kept). */
    public void replaceRecoveryCodes(String username, List<String> hashes) {
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = (SELECT id FROM users WHERE username = ?)").param(username).update();
        for (String hash : hashes) {
            jdbc.sql("INSERT INTO recovery_codes (user_id, code_hash) SELECT id, ? FROM users WHERE username = ?")
                    .params(hash, username).update();
        }
    }

    public List<RecoveryCode> unusedRecoveryCodes(String username) {
        return jdbc.sql("SELECT c.id, c.code_hash FROM recovery_codes c JOIN users u ON u.id = c.user_id "
                        + "WHERE u.username = ? AND c.used_at IS NULL ORDER BY c.id").param(username)
                .query((rs, n) -> new RecoveryCode(rs.getLong("id"), rs.getString("code_hash"))).list();
    }

    /** @return true when this call is the one that used the code */
    public boolean useRecoveryCode(long id) {
        return jdbc.sql("UPDATE recovery_codes SET used_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND used_at IS NULL")
                .param(id).update() == 1;
    }
}
