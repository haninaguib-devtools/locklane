package dev.locklane.engine.persistence;

import java.time.Instant;

/**
 * An account. {@code passwordHash} is a BCrypt hash — never the plaintext password.
 *
 * <p>{@code totpSecret} (#88) is the encrypted TOTP secret, or {@code null} when the
 * user has never started 2FA enrollment. It carries both states an enrollment can be
 * in, and {@code totpEnabled} is what tells them apart: a secret present with
 * {@code totpEnabled} false is pending — scanned, but not yet proved by a matching
 * code — and only {@code totpEnabled} true means two-factor authentication is on.
 *
 * <p>{@code role} (#238) is what {@link dev.locklane.engine.security.EngineUserDetailsService}
 * derives the account's Spring Security authority from. {@code mustChangePassword}
 * (#238, consumed by #240's forced-first-login flow) marks an admin-created account
 * that has to set its own password before it can use the app.
 */
public record UserRecord(
        long id,
        String username,
        String passwordHash,
        Instant createdAt,
        String totpSecret,
        boolean totpEnabled,
        Role role,
        boolean mustChangePassword) {

    /**
     * ADMIN can manage other accounts and every project; USER is an ordinary account
     * (#238). The account {@link dev.locklane.engine.security.UserBootstrapper} seeds
     * on first run is ADMIN; every account created after that defaults to USER unless
     * a human admin says otherwise (#239/#240, both out of scope here).
     */
    public enum Role {
        ADMIN,
        USER
    }
}
