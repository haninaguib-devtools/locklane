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
 * Login's success handler (#89): a correct password is no longer the whole story once
 * the account has 2FA on.
 *
 * <p>By the time this runs, Spring Security's login filter has already authenticated
 * the request and saved that authentication into the session — the ordinary case just
 * leaves it there. When the account has 2FA enabled, that has to be undone instead: the
 * security context is cleared and the session it was saved into is invalidated, and a
 * fresh session is started holding only the username, pending a code. {@link
 * AuthController#verifyTwoFactor} is what turns that pending session into an
 * authenticated one.
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
        boolean totpEnabled = userRepository.findByUsername(username)
                .map(UserRecord::totpEnabled)
                .orElse(false);

        if (!totpEnabled) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        SecurityContextHolder.clearContext();
        HttpSession authenticatedSession = request.getSession(false);
        if (authenticatedSession != null) {
            authenticatedSession.invalidate();
        }
        HttpSession pendingSession = request.getSession(true);
        pendingSession.setAttribute(PendingTwoFactorLogin.SESSION_ATTRIBUTE, username);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("twoFactorRequired", true));
    }
}
