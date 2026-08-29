package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * The "who am I" endpoint (#58): lets a freshly loaded client learn whether the
 * session cookie it already carries is still valid, so a page refresh does not
 * bounce a logged-in user to the login page. {@link SecurityConfig} gates it as
 * {@code authenticated()}, so an unauthenticated call never reaches here — it is
 * answered 401 by the entry point. The response body names the user; today's
 * client only reads the status code, but the identity is what the endpoint is
 * about and the UI will want a name to display eventually.
 *
 * <p>Also the other end of a 2FA login (#89): when {@link TwoFactorAwareLoginSuccessHandler}
 * has left a session pending a code, {@code /api/auth/2fa/verify} is what checks it and, on a
 * match, turns that pending session into an authenticated one. It has to stay outside
 * {@code authenticated()} — the request arriving here is, by definition, not authenticated yet.
 * A backup code (#93) works here too, in place of a TOTP code, for when the authenticator
 * device is unavailable.
 *
 * <p>And the other end of a forced-first-login password change (#238, #241): when {@link
 * TwoFactorAwareLoginSuccessHandler} has left a session pending a new password, {@code
 * /api/auth/password/change} is the mirror-image of {@code /api/auth/2fa/verify} — it checks
 * the (temporary) current password, replaces it, clears {@code must_change_password}, and
 * turns that pending session into an authenticated one. Also outside {@code authenticated()}
 * for the same reason.
 */
@RestController
public class AuthController {

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final TokenCipher tokenCipher;
    private final BackupCodeService backupCodeService;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(
            UserRepository userRepository,
            TotpService totpService,
            TokenCipher tokenCipher,
            BackupCodeService backupCodeService,
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.tokenCipher = tokenCipher;
        this.backupCodeService = backupCodeService;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * {@code role} (#240) is what the client gates its admin user-management panel's
     * visibility on ({@code AuthService.isAdmin}) — never itself an authorization
     * check, since every admin-only endpoint enforces that server-side regardless of
     * what a client believes about its own role.
     */
    @GetMapping("/api/auth/me")
    public Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName(), "role", roleOf(authentication.getName()));
    }

    /**
     * Checks the code against the account named in the pending session left by login, and on a
     * match authenticates it. A wrong code, or no pending session at all, leaves the request
     * exactly as unauthenticated as it arrived — the pending session (if any) is left in place so
     * a mistyped code can simply be retried. A code that is not currently a valid TOTP code is
     * also tried as a backup code (#93) before being rejected.
     */
    @PostMapping("/api/auth/2fa/verify")
    public ResponseEntity<?> verifyTwoFactor(
            HttpServletRequest request, HttpServletResponse response, @RequestBody CodeRequest body) {
        HttpSession session = request.getSession(false);
        String username = session == null
                ? null
                : (String) session.getAttribute(PendingTwoFactorLogin.SESSION_ATTRIBUTE);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "no login is pending a two-factor code"));
        }

        UserRecord user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.totpEnabled() || user.totpSecret() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "no login is pending a two-factor code"));
        }

        String secret = tokenCipher.decrypt(user.totpSecret());
        boolean verified = totpService.verify(secret, body.code(), Instant.now())
                || backupCodeService.consume(user.id(), body.code(), Instant.now());
        if (!verified) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "that code is not correct"));
        }

        session.removeAttribute(PendingTwoFactorLogin.SESSION_ATTRIBUTE);
        establishSession(request, response, username);

        return ResponseEntity.ok(Map.of("username", username, "role", roleOf(username)));
    }

    /**
     * Checks the (temporary) current password against the account named in the pending
     * session left by login, and on a match replaces it, clears {@code must_change_password},
     * and authenticates the session -- the mirror image of {@link #verifyTwoFactor}. A wrong
     * password, or no pending session at all, leaves the request exactly as unauthenticated as
     * it arrived, and the pending session (if any) is left in place so a mistyped current
     * password can simply be retried.
     */
    @PostMapping("/api/auth/password/change")
    public ResponseEntity<?> changePendingPassword(
            HttpServletRequest request, HttpServletResponse response, @RequestBody ChangePasswordRequest body) {
        HttpSession session = request.getSession(false);
        String username = session == null
                ? null
                : (String) session.getAttribute(PendingPasswordChangeLogin.SESSION_ATTRIBUTE);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "no login is pending a password change"));
        }

        UserRecord user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.mustChangePassword()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "no login is pending a password change"));
        }

        if (body.currentPassword() == null || !passwordEncoder.matches(body.currentPassword(), user.passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "that password is not correct"));
        }
        if (body.newPassword() == null || body.newPassword().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "the new password must not be blank"));
        }

        userRepository.changePassword(username, passwordEncoder.encode(body.newPassword()));
        session.removeAttribute(PendingPasswordChangeLogin.SESSION_ATTRIBUTE);
        establishSession(request, response, username);

        return ResponseEntity.ok(Map.of("username", username, "role", roleOf(username)));
    }

    /** {@code "USER"} for an account row that somehow doesn't exist any more — should never happen for an authenticated caller. */
    private String roleOf(String username) {
        return userRepository.findByUsername(username)
                .map(user -> user.role().name())
                .orElse(UserRecord.Role.USER.name());
    }

    /** Authenticates the current session as {@code username}, the last step of either pending flow. */
    private void establishSession(HttpServletRequest request, HttpServletResponse response, String username) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        userDetails, null, userDetails.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    public record CodeRequest(String code) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}
