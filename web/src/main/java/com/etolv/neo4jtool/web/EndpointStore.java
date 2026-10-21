package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.web.Endpoint.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Saved endpoints. A server password is stored encrypted and only decrypted to open the connection. */
@Repository
public class EndpointStore {

    private final JdbcClient jdbc;
    private final SecretBox secrets;

    public EndpointStore(JdbcClient jdbc, SecretBox secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public List<Endpoint> all() {
        return jdbc.sql("SELECT * FROM endpoints ORDER BY name").query(EndpointStore::map).list();
    }

    public Optional<Endpoint> find(long id) {
        return jdbc.sql("SELECT * FROM endpoints WHERE id = ?").param(id).query(EndpointStore::map).optional();
    }

    /**
     * @throws IllegalArgumentException when something is missing or the file or directory is not there
     * @throws org.springframework.dao.DuplicateKeyException when the name is taken
     */
    public long add(String name, Kind kind, String location, String username, String password, String database) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give it a name");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException(kind == Kind.LIVE ? "The Bolt URI is required" : "The path is required");
        }
        String db = database == null || database.isBlank() ? "neo4j" : database.trim();
        String where = location.trim();
        if (kind == Kind.DUMP && !Files.isRegularFile(Path.of(where))) {
            throw new IllegalArgumentException("There is no file " + where + " on the machine the tool runs on");
        }
        if (kind == Kind.STOPPED && !Files.isDirectory(Path.of(where, "databases", db))) {
            throw new IllegalArgumentException(where + " has no databases/" + db
                    + ". Give the data directory of the server: the one that contains databases/ and transactions/");
        }
        String encrypted = kind != Kind.LIVE || password == null || password.isEmpty() ? null : secrets.encrypt(password);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO endpoints (name, kind, uri, path, username, password_enc, database_name) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(name.trim(), kind.name(), kind == Kind.LIVE ? where : null, kind == Kind.LIVE ? null : where,
                        kind == Kind.LIVE && username != null && !username.isBlank() ? username.trim() : null, encrypted, db)
                .update(keys);
        return keys.getKey().longValue();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM endpoints WHERE id = ?").param(id).update();
    }

    /** The plain password of a LIVE endpoint, or null when it has none. */
    String password(Endpoint endpoint) {
        return endpoint.passwordEnc() == null ? null : secrets.decrypt(endpoint.passwordEnc());
    }

    private static Endpoint map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Endpoint(rs.getLong("id"), rs.getString("name"), Kind.valueOf(rs.getString("kind")),
                rs.getString("uri"), rs.getString("path"), rs.getString("username"), rs.getString("password_enc"),
                rs.getString("database_name"), rs.getTimestamp("created_at").toInstant());
    }
}
