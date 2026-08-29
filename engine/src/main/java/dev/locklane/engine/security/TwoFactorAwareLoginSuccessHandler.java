package dev.locklane.engine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Login's success handler (#89, #241): a correct password is no longer the whole story
 * once the account has 2FA on, or has been created with {@code must_change_password} set
 * (#238).
 *
 * <p>By the time this runs, Spring Security's login filter has already authenticated
 * the request and saved that authentication into the session — the ordinary case just
 * leaves it there. Either gate instead undoes that: the security context is cleared and
 * the session it was saved into is invalidated, and a fresh session is started holding
 * only the username, pending a second step. 2FA is checked first — an account that also
 * needs a password change gets to that only after clearing 2FA, via a normal, already
 * authenticated {@code /api/account/password} call (#241), since no account reachable
 * through this app's own flows ever has both pending at once. {@link
 * AuthController#verifyTwoFactor} turns the 2FA-pending session into an authenticated
 * one; {@link AuthController#changePendingPassword} does the same for the
 * password-change-pending one.
 */
@Component
class TwoFactorAwareLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    TwoFactorAwareLoginSuccessHandler(UserRepository userRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        String username = authentication.getName();
        UserRecord user = userRepository.findByUsername(username).orElse(null);

        if (user != null && user.totpEnabled()) {
            stagePending(request, PendingTwoFactorLogin.SESSION_ATTRIBUTE, username);
            respondPending(response, "twoFactorRequired");
            return;
        }

        if (user != null && user.mustChangePassword()) {
            stagePending(request, PendingPasswordChangeLogin.SESSION_ATTRIBUTE, username);
            respondPending(response, "mustChangePasswordRequired");
            return;
        }

        // Neither gate applies -- the session Spring Security's login filter already
        // saved stands as-is. The body names the role (#240) so the client can gate its
        // admin panel's visibility without a second round trip to /api/auth/me.
        response.setStatus(HttpServletResponse.SC_OK);
        if (user != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of("username", username, "role", user.role().name()));
        }
    }

    /**
     * Undoes the session Spring Security's login filter already established and starts a
     * fresh one holding only the username under {@code attribute}, pending whichever second
     * step that attribute names.
     */
    private void stagePending(HttpServletRequest request, String attribute, String username) {
        SecurityContextHolder.clearContext();
        HttpSession authenticatedSession = request.getSession(false);
        if (authenticatedSession != null) {
            authenticatedSession.invalidate();
        }
        HttpSession pendingSession = request.getSession(true);
        pendingSession.setAttribute(attribute, username);
    }

    private void respondPending(HttpServletResponse response, String flag) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(flag, true));
    }
}
