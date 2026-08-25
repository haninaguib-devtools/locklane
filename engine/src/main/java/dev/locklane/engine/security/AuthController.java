package dev.locklane.engine.security;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "who am I" endpoint (#58): lets a freshly loaded client learn whether the
 * session cookie it already carries is still valid, so a page refresh does not
 * bounce a logged-in user to the login page. {@link SecurityConfig} gates it as
 * {@code authenticated()}, so an unauthenticated call never reaches here — it is
 * answered 401 by the entry point. The response body names the user; today's
 * client only reads the status code, but the identity is what the endpoint is
 * about and the UI will want a name to display eventually.
 */
@RestController
public class AuthController {

    @GetMapping("/api/auth/me")
    public Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }
}
