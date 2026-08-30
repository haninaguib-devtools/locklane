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
 * {@code GET /api/auth/me} (#58). Login's success handler is
 * {@link TwoFactorAwareLoginSuccessHandler} (#89): an account with 2FA on gets a
 * pending session instead of an authenticated one, settled by
 * {@code POST /api/auth/2fa/verify}, which stays unauthenticated here on purpose —
 * the whole point of that endpoint is to authenticate a request that starts out not
 * being. Gated behind authentication: the two-factor
 * enrollment endpoints for the signed-in account (everything under
 * {@code /api/account/2fa/}, #88 — turning 2FA on and off is account
 * self-service, so it presupposes a session rather than establishing one), the
 * self-service password change at {@code /api/account/password} (#241 — same
 * reasoning; the forced-first-login version of that same change goes through the
 * deliberately unauthenticated {@code /api/auth/password/change} instead, the mirror
 * image of {@code /api/auth/2fa/verify} below); the worktree-session
 * endpoints (list/start under {@code /api/projects/{projectId}/issues/{number}/worktrees},
 * #48, nested under a project id since #43; the cross-issue listing under
 * {@code /api/projects/{projectId}/consoles}, #32), the project-level console
 * sessions under {@code /api/projects/{projectId}/console} and its sub-paths
 * (#139/#177 — same ownership story as a worktree session, just with no issue), the
 * project CRUD endpoints
 * (list/create at {@code /api/projects}, delete at {@code /api/projects/{id}},
 * retry at {@code /api/projects/{id}/retry}, #42; storing a project's GitHub token
 * at {@code /api/projects/{id}/github-token}, #81), the sidebar's usage widget
 * ({@code /api/usage}, #137 — it reads this host's own CLI credentials, not
 * per-project data, but is account-scoped the same way {@code /api/auth/me} is), the
 * detected-installed-agents endpoint backing the Settings dialog's default-agent picker
 * ({@code /api/agents/installed}, #359 — same account-scoped reasoning as the usage
 * widget above), and the WebSocket session endpoint itself (every path under
 * {@code /ws/sessions/}, #50) — its origin
 * restriction lives in {@code WebSocketConfig}, but authentication is enforced
 * here like every other endpoint. Issue/PR reads themselves stay open (no
 * per-user boundary) even though each project's own token, #81, scopes which
 * repo they come from — the matchers below are deliberately precise
 * (single path-segment wildcards, not a blanket match on everything under
 * {@code /api/projects}) so the open {@code /api/projects/{projectId}/issues}
 * paths never get swept into this gate.
 *
 * <p>Admin-only, on top of plain authentication: {@code /api/admin/**} (#240 —
 * {@link AdminUserController}'s user creation/deletion, the only way a second account
 * ever comes to exist, ADR-007 Decision 3). {@code hasRole("ADMIN")} implies
 * authentication, so an unauthenticated caller still gets 401 from the same entry
 * point as everything else; an authenticated non-admin caller gets Spring Security's
 * default 403, which is what #240's done-when asks for.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, TwoFactorAwareLoginSuccessHandler loginSuccessHandler) throws Exception {
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
                        .requestMatchers("/api/account/2fa/**").authenticated()
                        .requestMatchers("/api/account/password").authenticated()
                        .requestMatchers("/api/projects").authenticated()
                        .requestMatchers("/api/projects/*").authenticated()
                        .requestMatchers("/api/projects/*/retry").authenticated()
                        .requestMatchers("/api/projects/*/github-token").authenticated()
                        .requestMatchers("/api/projects/*/issues/*/worktrees").authenticated()
                        .requestMatchers("/api/projects/*/issues/*/worktrees/*").authenticated()
                        .requestMatchers("/api/projects/*/consoles").authenticated()
                        .requestMatchers("/api/projects/*/console").authenticated()
                        .requestMatchers("/api/projects/*/console/*").authenticated()
                        .requestMatchers("/api/usage").authenticated()
                        .requestMatchers("/api/agents/**").authenticated()
                        .requestMatchers("/ws/sessions/**").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler(loginSuccessHandler)
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
