package dev.locklane.engine.security;

import dev.locklane.engine.persistence.BackupCodeRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Backup codes (#93): a one-time-use way back into an account with 2FA on when the
 * authenticator device is lost. A set of ten codes is generated at once, each a
 * random 10-hex-digit value formatted as {@code XXXXX-XXXXX}, and shown to the user
 * exactly once as plaintext -- from then on only a BCrypt hash of each is kept
 * ({@link BackupCodeRepository}), the same pattern {@link AccountTwoFactorController}
 * already uses for the account password. A code is consumed the instant it is used
 * at login, so it never works a second time.
 */
@Component
public class BackupCodeService {

    private static final int CODE_COUNT = 10;
    private static final int CODE_BYTES = 5;

    private final BackupCodeRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public BackupCodeService(BackupCodeRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Generates a fresh set of codes and stores their hashes in place of whatever
     * set existed -- any code from the old set that had not yet been used stops
     * working. Returns the plaintext codes, the only moment they exist anywhere
     * outside the user's own copy of them.
     */
    public List<String> regenerate(long userId, Instant now) {
        List<String> codes = new ArrayList<>(CODE_COUNT);
        List<String> hashes = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = generateCode();
            codes.add(code);
            hashes.add(passwordEncoder.encode(code));
        }
        repository.replace(userId, hashes, now);
        return codes;
    }

    /**
     * Checks {@code code} against the user's unused codes and, on a match, consumes
     * it. Normalizes case and surrounding whitespace first, since the codes are
     * shown in uppercase but nothing about typing one back in guarantees a user
     * preserves that.
     */
    public boolean consume(long userId, String code, Instant now) {
        if (code == null) {
            return false;
        }
        String normalized = code.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return false;
        }
        for (BackupCodeRepository.BackupCodeRow row : repository.findUnused(userId)) {
            if (passwordEncoder.matches(normalized, row.codeHash())) {
                return repository.markUsed(row.id(), now);
            }
        }
        return false;
    }

    /** Forgets every code -- called alongside disabling TOTP, the same way the secret is forgotten. */
    public void clear(long userId) {
        repository.deleteAll(userId);
    }

    private String generateCode() {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        String hex = HexFormat.of().withUpperCase().formatHex(bytes);
        return hex.substring(0, 5) + "-" + hex.substring(5);
    }
}
