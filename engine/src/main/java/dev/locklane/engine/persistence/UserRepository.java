package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Accounts, in SQLite. Passwords are stored already hashed — this class never sees plaintext. */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public UserRecord create(String username, String passwordHash, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                username, passwordHash, now.toString());
        return findByUsername(username).orElseThrow();
    }

    public Optional<UserRecord> findByUsername(String username) {
        return jdbcTemplate.query(
                "SELECT id, username, password_hash, created_at FROM users WHERE username = ?",
                (rs, rowNum) -> toRecord(rs),
                username
        ).stream().findFirst();
    }

    public boolean anyExist() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count != null && count > 0;
    }

    private static UserRecord toRecord(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Instant.parse(rs.getString("created_at")));
    }
}
