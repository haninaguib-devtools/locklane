package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserCascadeDeleteService;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-only account management (#240, ADR-007 Decisions 3-4): there is no
 * self-registration anywhere in this app, so creating a second account, or removing
 * one, only ever happens here. {@link SecurityConfig} gates every path under
 * {@code /api/admin/**} with {@code hasRole("ADMIN")} — a non-admin (or unauthenticated)
 * caller never reaches a method body here; Spring Security answers 403 (authenticated
 * but wrong role) or 401 (no session at all) on its own before that.
 *
 * <p>Creating a user always sets {@code must_change_password} (#238), since the
 * password it starts with — whether the admin chose it or it was generated here — is
 * one the admin, not the new account holder, knows; #241's forced-first-login flow is
 * what makes that fact stop being true. A generated password is returned in the
 * response body exactly once, here — it is immediately BCrypt-hashed and never stored
 * or logged in the clear, so this is the only moment it can be handed to the admin to
 * pass along to the new account holder. An admin-supplied password is never echoed
 * back: the admin already knows it, so there is nothing new to show.
 *
 * <p>Deleting a user cascade-deletes everything it owned via
 * {@link UserCascadeDeleteService} (ADR-007 Decision 4) before the {@code users} row
 * itself is removed here.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository userRepository;
    private final UserCascadeDeleteService cascadeDeleteService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public AdminUserController(
            UserRepository userRepository,
            UserCascadeDeleteService cascadeDeleteService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.cascadeDeleteService = cascadeDeleteService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserView> list() {
        return userRepository.findAll().stream().map(UserView::from).toList();
    }

    /**
     * {@code password} is optional — blank/omitted generates a random temporary one,
     * returned once in the response; a caller-supplied one is used as-is and never
     * echoed back. Every account created here is an ordinary {@code USER} — promoting
     * an account to admin is not something this endpoint does.
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateUserRequest request) {
        String username = request.username() == null ? null : request.username().strip();
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "that username is already taken"));
        }

        boolean generated = request.password() == null || request.password().isBlank();
        String temporaryPassword = generated ? generateTemporaryPassword() : request.password();
        UserRecord created = userRepository.create(
                username, passwordEncoder.encode(temporaryPassword), Instant.now(), UserRecord.Role.USER, true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", UserView.from(created));
        if (generated) {
            body.put("temporaryPassword", temporaryPassword);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Cascade-deletes the account (ADR-007 Decision 4) — its owned projects, those
     * projects' on-disk workarea checkouts, and any worktree/console sessions scoped to
     * them — then the account row itself. 404 for an unknown id, indistinguishable from
     * a bad request; 409 rather than served for an admin's own account, so a caller can
     * never lock themselves out of the account they're using to make this very request.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, Authentication authentication) {
        Optional<UserRecord> target = userRepository.findById(id);
        if (target.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (target.get().username().equals(authentication.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "you cannot delete your own account"));
        }

        cascadeDeleteService.deleteEverythingOwnedBy(id);
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 24 bytes of {@link SecureRandom}, URL-safe base64 — long and random enough to be a one-time bearer credential. */
    private String generateTemporaryPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record CreateUserRequest(String username, String password) {
    }

    /** JSON shape for an account — never the password hash. */
    public record UserView(
            long id, String username, String role, boolean mustChangePassword, String createdAt) {
        static UserView from(UserRecord r) {
            return new UserView(r.id(), r.username(), r.role().name(), r.mustChangePassword(), r.createdAt().toString());
        }
    }
}
