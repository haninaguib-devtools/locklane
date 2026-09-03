package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * A device-flow GitHub account (#550) is now a token <em>pair</em> (#656,
 * {@code docs/architecture/github-token-lifetime.md}): GitHub issues OAuth Apps a
 * short-lived access token plus a long-lived refresh token, and the engine renews
 * the former with the latter instead of letting every project on the account die
 * an hour after sign-in. {@code refresh_token} is encrypted by {@code TokenCipher}
 * exactly like {@code token}; {@code token_expires_at} and
 * {@code refresh_token_expires_at} are ISO-8601 text like {@code created_at};
 * {@code renewal_failed_at} is set when a renewal was refused or the refresh token
 * itself ran out, which is what the accounts page shows as "needs reconnection". All
 * four are nullable and stay null for a pasted-token account, which is never renewed.
 * Every existing row is left exactly as it was.
 */
public class V17__AddRefreshTokenToGithubAccounts extends BaseJavaMigration {

    private static final String[] COLUMNS = {
            "refresh_token TEXT",
            "token_expires_at TEXT",
            "refresh_token_expires_at TEXT",
            "renewal_failed_at TEXT",
    };

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        for (String definition : COLUMNS) {
            String column = definition.substring(0, definition.indexOf(' '));
            if (!SqliteColumns.exists(connection, "github_accounts", column)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE github_accounts ADD COLUMN " + definition);
                }
            }
        }
    }
}
