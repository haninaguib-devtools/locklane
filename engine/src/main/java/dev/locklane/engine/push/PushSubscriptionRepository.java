package dev.locklane.engine.push;

import dev.locklane.engine.security.TokenCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * The {@code push_subscriptions} table (#860, V18): one row per browser that opted
 * in, owned by an account (ADR-105). The browser's {@code auth} secret is encrypted
 * at rest with {@link TokenCipher}, the same way a GitHub token is -- a database
 * copy alone must not be enough to push to someone's phone -- and handed back
 * decrypted by {@link #findAllOwnedBy}; {@code p256dh} is a public key and stays
 * plain. Re-registering an endpoint the table already knows updates it in place,
 * whoever owned it before: the endpoint is the browser's, and the account signed in
 * to that browser now is the one its notifications belong to.
 */
@Repository
public class PushSubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TokenCipher tokenCipher;

    public PushSubscriptionRepository(DataSource dataSource, TokenCipher tokenCipher) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tokenCipher = tokenCipher;
    }

    public void save(long ownerUserId, String endpoint, String p256dh, String auth, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO push_subscriptions (owner_user_id, endpoint, p256dh, auth, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(endpoint) DO UPDATE SET
                    owner_user_id = excluded.owner_user_id,
                    p256dh = excluded.p256dh,
                    auth = excluded.auth
                """, ownerUserId, endpoint, p256dh, tokenCipher.encrypt(auth), now.toString());
    }

    public List<PushSubscriptionRecord> findAllOwnedBy(long ownerUserId) {
        return jdbcTemplate.query("""
                SELECT id, owner_user_id, endpoint, p256dh, auth
                FROM push_subscriptions WHERE owner_user_id = ? ORDER BY id
                """,
                (rs, rowNum) -> new PushSubscriptionRecord(rs.getLong("id"), rs.getLong("owner_user_id"),
                        rs.getString("endpoint"), rs.getString("p256dh"), tokenCipher.decrypt(rs.getString("auth"))),
                ownerUserId);
    }

    /** Removes {@code endpoint} if {@code ownerUserId} owns it; another account's row is left alone. */
    public boolean delete(long ownerUserId, String endpoint) {
        return jdbcTemplate.update("DELETE FROM push_subscriptions WHERE owner_user_id = ? AND endpoint = ?",
                ownerUserId, endpoint) > 0;
    }

    /** Forgets an endpoint the push service reported gone, whoever owns it. */
    public void deleteByEndpoint(String endpoint) {
        jdbcTemplate.update("DELETE FROM push_subscriptions WHERE endpoint = ?", endpoint);
    }

    /** Every subscription of an account being deleted (ADR-101 Decision 4's cascade). */
    public void deleteAllOwnedBy(long ownerUserId) {
        jdbcTemplate.update("DELETE FROM push_subscriptions WHERE owner_user_id = ?", ownerUserId);
    }
}
