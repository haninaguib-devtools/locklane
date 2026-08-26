package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Turning two-factor authentication on and off for the signed-in account (#88).
 *
 * <p>Enrollment is deliberately two steps. {@code enroll} generates a secret and hands back a
 * QR code to scan; nothing is switched on yet, because a user who scans a code into the wrong
 * app — or no app — would otherwise lock themselves out at the next login. {@code confirm}
 * takes a code produced from that secret, which is the only evidence that the authenticator
 * actually holds it, and only then is 2FA on.
 *
 * <p>Turning it off costs the current password. A session cookie alone is enough to read
 * status and to start an enrollment, but it is not enough to remove a factor — otherwise 2FA
 * would protect nothing against whoever is holding that cookie.
 *
 * <p>Nothing here enforces 2FA at login; that is #89. Until it lands, enabling 2FA changes
 * what this API reports and nothing about how the user signs in.
 *
 * <p>{@link SecurityConfig} gates every path below as {@code authenticated()}, so
 * {@code authentication} is never null by the time a request arrives.
 */
@RestController
@RequestMapping("/api/account/2fa")
public class AccountTwoFactorController {

    /** What the authenticator app shows above the code, so a user with several can tell them apart. */
    private static final String ISSUER = "Locklane";

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final QrCodeRenderer qrCodeRenderer;
    private final TokenCipher tokenCipher;
    private final PasswordEncoder passwordEncoder;

    public AccountTwoFactorController(
            UserRepository userRepository,
            TotpService totpService,
            QrCodeRenderer qrCodeRenderer,
            TokenCipher tokenCipher,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.qrCodeRenderer = qrCodeRenderer;
        this.tokenCipher = tokenCipher;
        this.passwordEncoder = passwordEncoder;
    }

    /** Whether 2FA is actually on — a started-but-unconfirmed enrollment reports false. */
    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication authentication) {
        UserRecord user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new StatusResponse(user.totpEnabled()));
    }

    /**
     * Starts enrollment: a new secret, stored encrypted and still pending, returned as both a
     * QR code to scan and a manual key to type where a camera is not an option.
     *
     * <p>Re-running this before confirming is fine and simply replaces the pending secret — a
     * scan that went wrong should be retryable. Running it while 2FA is already <em>enabled</em>
     * is refused: moving 2FA to a different authenticator has to go through {@code disable},
     * which costs the password, so a stolen session cannot quietly repoint it.
     */
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(Authentication authentication) {
        String username = authentication.getName();
        UserRecord user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (user.totpEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "two-factor authentication is already enabled; disable it first"));
        }

        String secret = totpService.generateSecret();
        userRepository.startTotpEnrollment(username, tokenCipher.encrypt(secret));

        String uri = totpService.provisioningUri(ISSUER, username, secret);
        return ResponseEntity.ok(new EnrollResponse(qrCodeRenderer.toPngDataUri(uri), secret, uri));
    }

    /**
     * Finishes enrollment by proving the authenticator holds the pending secret. A wrong code
     * leaves the enrollment pending rather than discarding it, so the user can simply read the
     * next code off the same QR they already scanned.
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(Authentication authentication, @RequestBody CodeRequest request) {
        String username = authentication.getName();
        UserRecord user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (user.totpSecret() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "no enrollment in progress"));
        }
        if (user.totpEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "two-factor authentication is already enabled"));
        }

        String secret = tokenCipher.decrypt(user.totpSecret());
        if (!totpService.verify(secret, request.code(), Instant.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "that code is not correct"));
        }

        userRepository.enableTotp(username);
        return ResponseEntity.ok(new StatusResponse(true));
    }

    /**
     * Turns 2FA off and forgets the secret. Requires the current password: the session cookie
     * on its own must not be able to strip a factor off the account.
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disable(Authentication authentication, @RequestBody PasswordRequest request) {
        String username = authentication.getName();
        UserRecord user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.password() == null || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "that password is not correct"));
        }

        // Unconditional, so a pending enrollment is cleaned up by this too — not only an
        // enabled one. Already-off stays off, which makes the call safe to repeat.
        userRepository.disableTotp(username);
        return ResponseEntity.ok(new StatusResponse(false));
    }

    public record CodeRequest(String code) {
    }

    public record PasswordRequest(String password) {
    }

    public record StatusResponse(boolean enabled) {
    }

    /**
     * {@code qrCodeDataUri} drops straight into an {@code <img src>}; {@code manualKey} is the
     * same secret for hand entry; {@code otpauthUri} is what both encode, exposed so a client
     * can offer a "open in your authenticator" link instead of a scan.
     */
    public record EnrollResponse(String qrCodeDataUri, String manualKey, String otpauthUri) {
    }
}
