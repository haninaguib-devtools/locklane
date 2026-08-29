package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Self-service password change for the signed-in account (#241): current password plus a
 * new one, gated the same way {@link AccountTwoFactorController#disable} costs the current
 * password -- a session cookie alone must not be enough to replace it.
 *
 * <p>Also always clears {@code must_change_password} (#238) on success, whether or not it
 * was set: an ordinary voluntary change already satisfies whatever that flag was asking
 * for. The forced-first-login case itself (a session still only pending, left by {@link
 * TwoFactorAwareLoginSuccessHandler}) does not reach here at all -- that goes through
 * {@link AuthController#changePendingPassword}, which needs no prior session to complete.
 *
 * <p>{@link SecurityConfig} gates {@code /api/account/password} as {@code authenticated()},
 * so {@code authentication} is never null by the time a request arrives.
 */
@RestController
public class AccountPasswordController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountPasswordController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/api/account/password")
    public ResponseEntity<?> changePassword(Authentication authentication, @RequestBody ChangePasswordRequest body) {
        String username = authentication.getName();
        UserRecord user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.currentPassword() == null || !passwordEncoder.matches(body.currentPassword(), user.passwordHash())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "that password is not correct"));
        }
        if (body.newPassword() == null || body.newPassword().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "the new password must not be blank"));
        }

        userRepository.changePassword(username, passwordEncoder.encode(body.newPassword()));
        return ResponseEntity.ok().build();
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}
