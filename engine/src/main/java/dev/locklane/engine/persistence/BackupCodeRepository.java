package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * Backup codes (#93), in SQLite -- a one-time-use way back into an account with 2FA
 * on when the authenticator device is unavailable. Never sees a plaintext code:
 * callers hand in already-hashed values, the same as {@link UserRepository} does
 * with the TOTP secret.
 */
@Repository
public class BackupCodeRepository {

    private final JdbcTemplate jdbcTemplate;

    public BackupCodeRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Discards whatever set existed and stores a fresh one -- initial enable and regenerate alike. */
    public void replace(long userId, List<String> hashedCodes, Instant now) {
        jdbcTemplate.update("DELETE FROM backup_codes WHERE user_id = ?", userId);
        for (String hash : hashedCodes) {
            jdbcTemplate.update(
                    "INSERT INTO backup_codes (user_id, code_hash, created_at) VALUES (?, ?, ?)",
                    userId, hash, now.toString());
        }
    }

    /** The still-usable hashes for a user, to check a login attempt against. */
    public List<BackupCodeRow> findUnused(long userId) {
        return jdbcTemplate.query(
                "SELECT id, code_hash FROM backup_codes WHERE user_id = ? AND used_at IS NULL",
                (rs, rowNum) -> new BackupCodeRow(rs.getLong("id"), rs.getString("code_hash")),
                userId);
    }

    /**
     * Marks one code used, scoped to it still being unused -- guards two concurrent
     * logins racing to consume the same code. Returns whether this call was the one
     * that consumed it.
     */
    public boolean markUsed(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE backup_codes SET used_at = ? WHERE id = ? AND used_at IS NULL",
                now.toString(), id) == 1;
    }

    /** Forgets every code for a user -- paired with disabling TOTP forgetting the secret. */
    public void deleteAll(long userId) {
        jdbcTemplate.update("DELETE FROM backup_codes WHERE user_id = ?", userId);
    }

    public record BackupCodeRow(long id, String codeHash) {
    }
}
