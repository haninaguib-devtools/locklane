package dev.locklane.engine.usage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** The real {@link UsageHttpClient}: the JDK's own client, no new dependency needed. */
public class JdkUsageHttpClient implements UsageHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public Optional<String> get(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
            headers.forEach(request::header);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (IOException e) {
            // silent: a network hiccup against a best-effort usage endpoint degrades
            // to unavailable, never a broken sidebar (see the providers' own doc).
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // silent: same as above.
            return Optional.empty();
        }
    }
}
