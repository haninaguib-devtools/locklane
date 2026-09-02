package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The real {@link GhDeviceFlow} (#550): GitHub's two device-flow endpoints over
 * plain HTTPS, {@code Accept: application/json} so the response is JSON instead of
 * GitHub's default form-encoded body.
 */
public class HttpGhDeviceFlow implements GhDeviceFlow {

    private static final Logger log = LoggerFactory.getLogger(HttpGhDeviceFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI GITHUB_DEVICE_CODE_URI = URI.create("https://github.com/login/device/code");
    private static final URI GITHUB_ACCESS_TOKEN_URI = URI.create("https://github.com/login/oauth/access_token");
    private static final int DEFAULT_EXPIRES_IN_SECONDS = 900;
    private static final int DEFAULT_INTERVAL_SECONDS = 5;

    private final HttpClient httpClient;
    private final URI deviceCodeUri;
    private final URI accessTokenUri;

    public HttpGhDeviceFlow() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                GITHUB_DEVICE_CODE_URI, GITHUB_ACCESS_TOKEN_URI);
    }

    /** Test-only: a client (and endpoints) pointed at a local stub server instead of the real github.com. */
    HttpGhDeviceFlow(HttpClient httpClient, URI deviceCodeUri, URI accessTokenUri) {
        this.httpClient = httpClient;
        this.deviceCodeUri = deviceCodeUri;
        this.accessTokenUri = accessTokenUri;
    }

    @Override
    public DeviceCode start(String clientId, String scope) {
        JsonNode node = post(deviceCodeUri, Map.of("client_id", clientId, "scope", scope));
        if (node.has("error")) {
            throw new GhDeviceFlowException("GitHub refused to start a device flow: "
                    + node.path("error_description").asText(node.path("error").asText()));
        }
        return new DeviceCode(
                node.path("device_code").asText(),
                node.path("user_code").asText(),
                node.path("verification_uri").asText(),
                node.path("expires_in").asInt(DEFAULT_EXPIRES_IN_SECONDS),
                node.path("interval").asInt(DEFAULT_INTERVAL_SECONDS));
    }

    @Override
    public PollResult poll(String clientId, String deviceCode) {
        JsonNode node;
        try {
            node = post(accessTokenUri, Map.of(
                    "client_id", clientId,
                    "device_code", deviceCode,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
        } catch (GhDeviceFlowException e) {
            log.info("Device-flow poll failed", e);
            return new PollResult.Error(e.getMessage());
        }
        if (node.has("access_token")) {
            return new PollResult.Success(node.path("access_token").asText());
        }
        String error = node.path("error").asText("");
        return switch (error) {
            case "authorization_pending" -> new PollResult.Pending();
            case "slow_down" -> new PollResult.SlowDown();
            case "expired_token" -> new PollResult.Expired();
            case "access_denied" -> new PollResult.Denied();
            default -> new PollResult.Error(error.isBlank() ? "unexpected response from GitHub" : error);
        };
    }

    private JsonNode post(URI uri, Map<String, String> form) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new GhDeviceFlowException("Could not reach GitHub (" + uri + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhDeviceFlowException("Interrupted while calling GitHub (" + uri + ")", e);
        }
    }

    private static String formEncode(Map<String, String> form) {
        // LinkedHashMap callers keep a stable, readable order; not load-bearing.
        Map<String, String> ordered = new LinkedHashMap<>(form);
        return ordered.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    public static class GhDeviceFlowException extends RuntimeException {
        public GhDeviceFlowException(String message) {
            super(message);
        }

        public GhDeviceFlowException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
