package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Durable GitHub accounts (#550) — each owned by exactly one Locklane user
 * (ADR-105). Alongside {@link ProjectRepository} rather than under
 * {@code dev.locklane.engine.github}: every other durable-row repository in the
 * engine lives here, and {@link GhAccount} itself stays in the {@code github}
 * package it was already in.
 */
@Repository
public class GhAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public GhAccountRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Inserts a new account, its token already encrypted (callers never hand this plaintext through). */
    public GhAccount insert(long ownerUserId, String login, String encryptedToken, Set<String> scopes, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO github_accounts (owner_user_id, login, token, scopes, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                ownerUserId, login, encryptedToken, joinScopes(scopes), now.toString());
        // Not last_insert_rowid(): JdbcTemplate does not guarantee this query reuses
        // the same physical connection the insert just ran on, and that value is
        // connection-local. The encrypted token is effectively unique (AES-GCM's
        // random IV, TokenCipher) -- the same lookup shape ProjectRepository#create
        // uses via workareaPath.
        return jdbcTemplate.query(
                "SELECT id, owner_user_id, login, scopes, created_at FROM github_accounts "
                        + "WHERE owner_user_id = ? AND token = ?",
                (rs, rowNum) -> toAccount(rs),
                ownerUserId, encryptedToken
        ).get(0);
    }

    /** Never carries the token — that is {@link #findEncryptedToken}, an engine-internal lookup alone. */
    public Optional<GhAccount> findById(long id) {
        return jdbcTemplate.query(
                "SELECT id, owner_user_id, login, scopes, created_at FROM github_accounts WHERE id = ?",
                (rs, rowNum) -> toAccount(rs),
                id
        ).stream().findFirst();
    }

    /** Every account {@code ownerUserId} added, newest first — the accounts page's own listing. */
    public List<GhAccount> findAllOwnedBy(long ownerUserId) {
        return jdbcTemplate.query(
                "SELECT id, owner_user_id, login, scopes, created_at FROM github_accounts "
                        + "WHERE owner_user_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> toAccount(rs),
                ownerUserId);
    }

    /**
     * The account's still-encrypted token, for the engine's own git/gh operations
     * (#550, #551) — callers decrypt via {@code TokenCipher}; this never sees
     * plaintext and never returns it to an API caller. Empty for an unknown id.
     */
    public Optional<String> findEncryptedToken(long id) {
        return jdbcTemplate.query(
                "SELECT token FROM github_accounts WHERE id = ?",
                (rs, rowNum) -> rs.getString("token"),
                id
        ).stream().findFirst();
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM github_accounts WHERE id = ?", id);
    }

    private static GhAccount toAccount(ResultSet rs) throws SQLException {
        return new GhAccount(
                rs.getLong("id"),
                rs.getLong("owner_user_id"),
                rs.getString("login"),
                splitScopes(rs.getString("scopes")),
                Instant.parse(rs.getString("created_at")));
    }

    private static String joinScopes(Set<String> scopes) {
        return String.join(",", scopes);
    }

    private static Set<String> splitScopes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(scopes::add);
        return scopes;
    }
}
