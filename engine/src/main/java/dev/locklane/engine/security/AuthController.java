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
 */
@RestController
public class AuthController {

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final TokenCipher tokenCipher;
    private final BackupCodeService backupCodeService;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(
            UserRepository userRepository,
            TotpService totpService,
            TokenCipher tokenCipher,
            BackupCodeService backupCodeService,
            UserDetailsService userDetailsService) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.tokenCipher = tokenCipher;
        this.backupCodeService = backupCodeService;
        this.userDetailsService = userDetailsService;
    }

    @GetMapping("/api/auth/me")
    public Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
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

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        userDetails, null, userDetails.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(Map.of("username", username));
    }

    public record CodeRequest(String code) {
    }
}
