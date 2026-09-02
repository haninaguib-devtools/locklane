package dev.locklane.engine.github;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #590: the engine ships the registered "Locklane" OAuth App's client id as its
 * built-in default, so "Sign in with GitHub" works on a plain install with no
 * per-host configuration. Reads the shipped {@code application.yml} itself (the exact
 * artifact that carries the default) and drives {@link GhAccountsService} with that
 * value, so the default cannot silently regress to blank — which would turn every
 * device-flow start back into a 501 (#550's old manual-step state).
 */
class GhAccountsServiceDefaultClientIdTest {

    private static final String SHIPPED_CLIENT_ID = "Ov23lifzJ18NqTqgqdRn";

    @Test
    void theShippedDefaultClientIdIsTheRegisteredLocklaneApp() {
        assertThat(shippedClientId()).isEqualTo(SHIPPED_CLIENT_ID);
    }

    @Test
    void startDeviceFlowIsConfiguredUnderTheShippedDefault(@TempDir Path tmp) {
        AtomicReference<String> startedWith = new AtomicReference<>();
        GhDeviceFlow deviceFlow = new GhDeviceFlow() {
            @Override
            public DeviceCode start(String clientId, String scope) {
                startedWith.set(clientId);
                return new DeviceCode("device-code", "ABCD-EFGH", "https://github.com/login/device", 900, 5);
            }

            @Override
            public PollResult poll(String clientId, String deviceCode) {
                return new PollResult.Pending();
            }
        };
        GhAccountsService service = new GhAccountsService(TestSqliteDatabases.newGhAccountRepository(tmp),
                TestSqliteDatabases.newProjectRepository(tmp), tokenCipher(tmp),
                new FakeIntrospector(), deviceFlow, task -> { }, shippedClientId());

        GhAccountsService.DeviceFlowStartResult result = service.startDeviceFlow(1L);

        assertThat(result).isInstanceOf(GhAccountsService.DeviceFlowStartResult.Started.class);
        assertThat(startedWith.get()).isEqualTo(SHIPPED_CLIENT_ID);
    }

    /**
     * {@code locklane.github.oauth-client-id} as the shipped {@code application.yml}
     * declares it. Read from the source tree, not the classpath: the test classpath
     * carries its own {@code application.yml} (test-only overrides) that shadows the
     * shipped one, and the shipped file is exactly what #590's own done-when greps.
     */
    private static String shippedClientId() {
        Path shipped = Path.of("src/main/resources/application.yml");
        assertThat(shipped).as("the shipped application.yml, relative to the engine module").isRegularFile();
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(shipped));
        Properties properties = yaml.getObject();
        assertThat(properties).as("parsed application.yml").isNotNull();
        String clientId = properties.getProperty("locklane.github.oauth-client-id");
        assertThat(clientId).as("locklane.github.oauth-client-id in application.yml").isNotBlank();
        return clientId;
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Never reached by this test — {@code startDeviceFlow} introspects nothing. */
    private static final class FakeIntrospector extends GhTokenIntrospector {
        FakeIntrospector() {
            super(token -> {
                throw new AssertionError("must not run the real gh subprocess");
            });
        }

        @Override
        public Optional<Introspection> introspect(String token) {
            throw new AssertionError("startDeviceFlow must not introspect a token");
        }
    }
}
