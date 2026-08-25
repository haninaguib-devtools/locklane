package dev.locklane.engine.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

/**
 * Wires up session-based login for {@code POST /api/auth/login} /
 * {@code POST /api/auth/logout} (#47) plus the session check at
 * {@code GET /api/auth/me} (#58), and gates behind it: the worktree-session
 * endpoints (list/start, {@code /api/issues/*}{@code /worktrees}, #48; the
 * cross-issue listing at {@code /api/consoles}, #32), and the WebSocket session
 * endpoint itself ({@code /ws/sessions/**}, #50) — its origin restriction lives in
 * {@code WebSocketConfig}, but authentication is enforced here like every other
 * endpoint. Issue/PR data stays open: it still comes from one shared repo with no
 * per-user boundary until #41 gives it one.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Still no CSRF-token flow — the gated endpoints below are
                // cookie-authenticated, state-changing (worktree creation) or
                // long-lived (the WebSocket handshake), so this is a real (if
                // lower-severity than "no auth at all") gap being knowingly carried
                // forward across #48, #49, and now #50: the fix needs an
                // Angular-side change (reading a CSRF cookie, sending it back as a
                // header) outside engine/**'s scope, and #49's client work landed
                // without adding one either.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/issues/*/worktrees").authenticated()
                        .requestMatchers("/api/consoles").authenticated()
                        .requestMatchers("/ws/sessions/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_OK))
                        .failureHandler((request, response, exception) ->
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_OK)))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
