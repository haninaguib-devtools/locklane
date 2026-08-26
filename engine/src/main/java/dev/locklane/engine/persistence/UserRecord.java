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
 */
public record UserRecord(
        long id,
        String username,
        String passwordHash,
        Instant createdAt,
        String totpSecret,
        boolean totpEnabled) {
}
