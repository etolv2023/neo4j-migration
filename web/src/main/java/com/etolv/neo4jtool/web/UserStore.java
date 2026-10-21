package com.etolv.neo4jtool.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;

/** Dashboard users. Passwords are kept as BCrypt hashes. */
@Repository
public class UserStore implements UserDetailsService {

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("SELECT username, password_hash FROM users WHERE username = ?").param(username)
                .query((rs, n) -> User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                        .roles("ADMIN").build())
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
}
