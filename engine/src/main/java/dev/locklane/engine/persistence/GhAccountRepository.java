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
 *
 * <p>Since #656 a device-flow account also carries an encrypted refresh token and
 * the lifetimes of both tokens; neither secret is ever part of a {@link GhAccount}.
 */
@Repository
public class GhAccountRepository {

    private static final String ACCOUNT_COLUMNS = "id, owner_user_id, login, scopes, created_at, "
            + "token_expires_at, refresh_token_expires_at, renewal_failed_at";

    private final JdbcTemplate jdbcTemplate;

    public GhAccountRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Inserts a non-expiring account (a pasted token, #550), its token already encrypted. */
    public GhAccount insert(long ownerUserId, String login, String encryptedToken, Set<String> scopes, Instant now) {
        return insert(ownerUserId, login, encryptedToken, scopes, now, null, null, null);
    }

    /**
     * Inserts an account whose tokens are already encrypted (callers never hand
     * plaintext through). {@code encryptedRefreshToken} and the two expiries are
     * {@code null} for a token that does not expire (#656).
     */
    public GhAccount insert(long ownerUserId, String login, String encryptedToken, Set<String> scopes, Instant now,
            String encryptedRefreshToken, Instant tokenExpiresAt, Instant refreshTokenExpiresAt) {
        jdbcTemplate.update("""
                INSERT INTO github_accounts (owner_user_id, login, token, scopes, created_at,
                    refresh_token, token_expires_at, refresh_token_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ownerUserId, login, encryptedToken, joinScopes(scopes), now.toString(),
                encryptedRefreshToken, text(tokenExpiresAt), text(refreshTokenExpiresAt));
        // Not last_insert_rowid(): JdbcTemplate does not guarantee this query reuses
        // the same physical connection the insert just ran on, and that value is
        // connection-local. The encrypted token is effectively unique (AES-GCM's
        // random IV, TokenCipher) -- the same lookup shape ProjectRepository#create
        // uses via workareaPath.
        return jdbcTemplate.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM github_accounts WHERE owner_user_id = ? AND token = ?",
                (rs, rowNum) -> toAccount(rs),
                ownerUserId, encryptedToken
        ).get(0);
    }

    /** Never carries a token — those are {@link #findEncryptedToken} and {@link #findEncryptedRefreshToken}, engine-internal lookups alone. */
    public Optional<GhAccount> findById(long id) {
        return jdbcTemplate.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM github_accounts WHERE id = ?",
                (rs, rowNum) -> toAccount(rs),
                id
        ).stream().findFirst();
    }

    /** Every account {@code ownerUserId} added, newest first — the accounts page's own listing. */
    public List<GhAccount> findAllOwnedBy(long ownerUserId) {
        return jdbcTemplate.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM github_accounts WHERE owner_user_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> toAccount(rs),
                ownerUserId);
    }

    /**
     * Accounts the renewal pass should act on (#656): those with a refresh token, not
     * already marked as failed, whose access token expires at or before
     * {@code before}. Ordered by id so a run is deterministic.
     */
    public List<GhAccount> findDueForRenewal(Instant before) {
        return jdbcTemplate.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM github_accounts WHERE refresh_token IS NOT NULL "
                        + "AND renewal_failed_at IS NULL AND token_expires_at IS NOT NULL AND token_expires_at <= ? "
                        + "ORDER BY id",
                (rs, rowNum) -> toAccount(rs),
                before.toString());
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

    /** The account's still-encrypted refresh token (#656); empty for an unknown id or a non-expiring account. */
    public Optional<String> findEncryptedRefreshToken(long id) {
        return jdbcTemplate.query(
                "SELECT refresh_token FROM github_accounts WHERE id = ?",
                (rs, rowNum) -> rs.getString("refresh_token"),
                id
        ).stream().filter(token -> token != null).findFirst();
    }

    /**
     * Stores a renewed pair (#656) — both already encrypted — with its new expiries,
     * and clears {@code renewal_failed_at}: a renewal that worked means the account
     * is healthy again, whatever happened before.
     */
    public void updateTokens(long id, String encryptedToken, String encryptedRefreshToken, Instant tokenExpiresAt,
            Instant refreshTokenExpiresAt) {
        jdbcTemplate.update("""
                UPDATE github_accounts SET token = ?, refresh_token = ?, token_expires_at = ?,
                    refresh_token_expires_at = ?, renewal_failed_at = NULL
                WHERE id = ?
                """,
                encryptedToken, encryptedRefreshToken, text(tokenExpiresAt), text(refreshTokenExpiresAt), id);
    }

    /** Records that renewing this account failed for good (#656); {@link #findDueForRenewal} skips it from now on. */
    public void markRenewalFailed(long id, Instant now) {
        jdbcTemplate.update("UPDATE github_accounts SET renewal_failed_at = ? WHERE id = ?", now.toString(), id);
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
                Instant.parse(rs.getString("created_at")),
                instantOrNull(rs.getString("token_expires_at")),
                instantOrNull(rs.getString("refresh_token_expires_at")),
                instantOrNull(rs.getString("renewal_failed_at")));
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instantOrNull(String text) {
        return text == null ? null : Instant.parse(text);
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
