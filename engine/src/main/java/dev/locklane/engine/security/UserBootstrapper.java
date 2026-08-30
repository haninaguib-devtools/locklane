package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Seeds a single admin account on first run, so there is always at least one user to
 * log in as (#47), and it is one who can administer the app (#238) — full multi-user
 * management (invite/create/delete via UI) is out of scope here. No-op once any user
 * exists.
 *
 * <p><b>Seed-only mode</b> (#384) is how {@code install.sh} creates that account while
 * it is still holding the password the person just typed, instead of writing the
 * password into {@code ~/.locklane/application-locklane.properties} and leaving it
 * there for ever to be consumed by exactly one later startup. The installer starts this
 * same jar once with {@code locklane.security.seed-only=true} and the credentials in
 * the environment (never on the command line, so they never appear in {@code ps}), and
 * the run stops as soon as the account is in the database. Nothing else about that run
 * differs from an ordinary start — same data directory, same migrations, same
 * hashing — so the account the installer creates is exactly the account the server
 * later logs in.
 *
 * <p>A seeding run that finds an account already there exits {@link #EXIT_ALREADY_SEEDED}
 * rather than 0, so the installer can say plainly that the credentials just typed were
 * not applied instead of claiming a new account was made.
 */
@Component
public class UserBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapper.class);

    /** Seed-only exit status for "there was already an account; nothing was created" (#384). */
    public static final int EXIT_ALREADY_SEEDED = 3;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationContext applicationContext;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final boolean seedOnly;

    public UserBootstrapper(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            ApplicationContext applicationContext,
            @Value("${locklane.security.bootstrap-username}") String bootstrapUsername,
            @Value("${locklane.security.bootstrap-password}") String bootstrapPassword,
            @Value("${locklane.security.seed-only:false}") boolean seedOnly) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationContext = applicationContext;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        // Defaulted inline rather than required: src/test/resources/application.yml
        // replaces the main one wholesale, so a mandatory placeholder here would have
        // to be repeated there too.
        this.seedOnly = seedOnly;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean created = seed();
        if (!seedOnly) {
            return;
        }
        if (!created) {
            log.warn("An account already exists — the username and password given to this seeding "
                    + "run were not applied. Sign in with the account you already have.");
        }
        // The installer wants nothing from this JVM but the account (#384): shut the
        // context down here rather than leaving a server listening.
        int status = created ? 0 : EXIT_ALREADY_SEEDED;
        System.exit(SpringApplication.exit(applicationContext, () -> status));
    }

    /** Creates the admin account if there is not already a user. True if it created one. */
    private boolean seed() {
        if (userRepository.anyExist()) {
            return false;
        }
        userRepository.create(bootstrapUsername, passwordEncoder.encode(bootstrapPassword), Instant.now(),
                UserRecord.Role.ADMIN);
        log.warn("No users existed yet — created bootstrap user '{}'. Override "
                        + "locklane.security.bootstrap-username/-password before any real deployment.",
                bootstrapUsername);
        return true;
    }
}
