package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Seeds a single account on first run, so there is always at least one user to log in
 * as (#47) — full multi-user management (invite/create/delete via UI) is out of scope
 * here. No-op once any user exists.
 */
@Component
public class UserBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapper.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public UserBootstrapper(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${locklane.security.bootstrap-username}") String bootstrapUsername,
            @Value("${locklane.security.bootstrap-password}") String bootstrapPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.anyExist()) {
            return;
        }
        userRepository.create(bootstrapUsername, passwordEncoder.encode(bootstrapPassword), Instant.now());
        log.warn("No users existed yet — created bootstrap user '{}'. Override "
                        + "locklane.security.bootstrap-username/-password before any real deployment.",
                bootstrapUsername);
    }
}
