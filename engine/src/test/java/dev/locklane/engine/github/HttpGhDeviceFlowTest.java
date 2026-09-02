package dev.locklane.engine.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpGhDeviceFlow} against a local {@link HttpServer} stub instead of the
 * real {@code github.com} — no network, no {@code java.net.http.HttpClient} mocking
 * library needed for a shape this simple. The stub always answers whatever the test
 * queued, ignoring the actual URI (the two GitHub endpoints are told apart by which
 * method the test calls, not by inspecting the request here).
 */
class HttpGhDeviceFlowTest {

    private HttpServer server;
    private final AtomicReference<String> nextResponse = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startParsesTheDeviceCodeResponse() throws Exception {
        HttpGhDeviceFlow deviceFlow = deviceFlowOverStub("""
                {"device_code":"dc123","user_code":"ABCD-1234",
                 "verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}
                """);

        GhDeviceFlow.DeviceCode code = deviceFlow.start("client-id", "repo workflow read:org");

        assertThat(code.deviceCode()).isEqualTo("dc123");
        assertThat(code.userCode()).isEqualTo("ABCD-1234");
        assertThat(code.verificationUri()).isEqualTo("https://github.com/login/device");
        assertThat(code.expiresInSeconds()).isEqualTo(900);
        assertThat(code.intervalSeconds()).isEqualTo(5);
        assertThat(lastRequestBody.get()).contains("client_id=client-id")
                .contains("scope=repo+workflow+read%3Aorg");
    }

    @Test
    void startThrowsWhenGitHubRefuses() throws Exception {
        HttpGhDeviceFlow deviceFlow = deviceFlowOverStub(
                "{\"error\":\"invalid_client\",\"error_description\":\"client id not found\"}");

        assertThat(catchThrown(() -> deviceFlow.start("bad-client", "repo")))
                .isInstanceOf(HttpGhDeviceFlow.GhDeviceFlowException.class)
                .hasMessageContaining("client id not found");
    }

    @Test
    void pollReadsAnAccessTokenAsSuccess() throws Exception {
        HttpGhDeviceFlow deviceFlow = deviceFlowOverStub(
                "{\"access_token\":\"gho_abc123\",\"token_type\":\"bearer\",\"scope\":\"\"}");

        GhDeviceFlow.PollResult result = deviceFlow.poll("client-id", "dc123");

        assertThat(result).isInstanceOf(GhDeviceFlow.PollResult.Success.class);
        assertThat(((GhDeviceFlow.PollResult.Success) result).accessToken()).isEqualTo("gho_abc123");
        assertThat(lastRequestBody.get()).contains("device_code=dc123")
                .contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code");
    }

    @Test
    void pollMapsEveryKnownErrorCode() throws Exception {
        assertThat(pollWithError("authorization_pending")).isInstanceOf(GhDeviceFlow.PollResult.Pending.class);
        assertThat(pollWithError("slow_down")).isInstanceOf(GhDeviceFlow.PollResult.SlowDown.class);
        assertThat(pollWithError("expired_token")).isInstanceOf(GhDeviceFlow.PollResult.Expired.class);
        assertThat(pollWithError("access_denied")).isInstanceOf(GhDeviceFlow.PollResult.Denied.class);
        assertThat(pollWithError("something_else")).isInstanceOf(GhDeviceFlow.PollResult.Error.class);
    }

    @Test
    void pollIsAnErrorWhenTheServerIsUnreachable() {
        // A host+port nothing listens on (the same trick ProjectCheckoutServiceTest
        // uses) -- no real network access needed to prove the IOException path maps
        // to Error rather than throwing out of poll().
        HttpGhDeviceFlow deviceFlow = new HttpGhDeviceFlow(java.net.http.HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:1/device/code"), URI.create("http://127.0.0.1:1/access_token"));

        GhDeviceFlow.PollResult result = deviceFlow.poll("client-id", "dc123");

        assertThat(result).isInstanceOf(GhDeviceFlow.PollResult.Error.class);
    }

    private GhDeviceFlow.PollResult pollWithError(String error) throws Exception {
        HttpGhDeviceFlow deviceFlow = deviceFlowOverStub("{\"error\":\"" + error + "\"}");
        return deviceFlow.poll("client-id", "dc123");
    }

    private static RuntimeException catchThrown(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected an exception, but none was thrown");
    }

    /**
     * A local stub that always answers {@code body} regardless of path — good
     * enough here since {@code start}/{@code poll} are exercised one at a time,
     * never against the same server instance expecting different answers per call.
     */
    private HttpGhDeviceFlow deviceFlowOverStub(String body) throws IOException {
        if (server != null) {
            server.stop(0);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        // Same package as HttpGhDeviceFlow -- its test-only constructor is directly
        // reachable, no reflection needed.
        return new HttpGhDeviceFlow(java.net.http.HttpClient.newHttpClient(), base.resolve("/device/code"),
                base.resolve("/access_token"));
    }
}
