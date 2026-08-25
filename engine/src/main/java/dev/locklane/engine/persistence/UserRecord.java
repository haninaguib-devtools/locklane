package dev.locklane.engine.persistence;

import java.time.Instant;

/** An account. {@code passwordHash} is a BCrypt hash — never the plaintext password. */
public record UserRecord(
        long id,
        String username,
        String passwordHash,
        Instant createdAt) {
}
