package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Accounts, in SQLite. Passwords are stored already hashed — this class never sees
 * plaintext. The same holds for the TOTP secret (#88): callers hand in an encrypted
 * value, and this class only moves it in and out of the column.
 */
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
                "SELECT id, username, password_hash, created_at, totp_secret, totp_enabled "
                        + "FROM users WHERE username = ?",
                (rs, rowNum) -> toRecord(rs),
                username
        ).stream().findFirst();
    }

    public boolean anyExist() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count != null && count > 0;
    }

    /**
     * Stores a freshly generated, still-unproved TOTP secret (#88) — already encrypted by
     * the caller. Leaves {@code totp_enabled} at 0: the secret is pending until a matching
     * code confirms it, so a half-finished enrollment never turns 2FA on by itself.
     */
    public void startTotpEnrollment(String username, String encryptedSecret) {
        jdbcTemplate.update(
                "UPDATE users SET totp_secret = ?, totp_enabled = 0 WHERE username = ?",
                encryptedSecret, username);
    }

    /**
     * Marks the pending secret proved and 2FA on (#88). Scoped to a row that actually has a
     * secret, so this can never enable 2FA against a NULL secret and lock the account out of
     * a factor it has no way to produce.
     *
     * <p>Returns the number of rows changed — 1 when the enrollment was there to confirm, 0
     * when it was not. The caller has to look: the guard above means a cleared secret makes
     * this a no-op, and reporting 2FA on after a no-op would tell the user they have a second
     * factor they do not have.
     */
    public int enableTotp(String username) {
        return jdbcTemplate.update(
                "UPDATE users SET totp_enabled = 1 WHERE username = ? AND totp_secret IS NOT NULL",
                username);
    }

    /** Clears the secret and turns 2FA off (#88), whether it was pending or enabled. */
    public void disableTotp(String username) {
        jdbcTemplate.update(
                "UPDATE users SET totp_secret = NULL, totp_enabled = 0 WHERE username = ?",
                username);
    }

    private static UserRecord toRecord(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Instant.parse(rs.getString("created_at")),
                rs.getString("totp_secret"),
                rs.getBoolean("totp_enabled"));
    }
}
