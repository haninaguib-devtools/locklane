package dev.locklane.engine.codeserver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Forwards plain HTTP traffic under {@code /api/projects/{projectId}/consoles/{id}/ide/}
 * to that console's loopback code-server (#655) — pages, scripts, the editor's
 * resource and extension requests — once {@link CodeServerProxyAuthorization} has
 * admitted the caller. Mapped by {@link CodeServerProxyConfig} behind the WebSocket
 * handler mapping, so an {@code Upgrade: websocket} request on the same path family
 * reaches {@link CodeServerWebSocketProxy} instead of this.
 *
 * <p>Bodies pass through untouched in both directions ({@code Accept-Encoding} and
 * {@code Content-Encoding} included), streamed rather than buffered. Hop-by-hop
 * headers, {@code Host}, and every {@code Forwarded}/{@code X-Forwarded-*} header are
 * dropped on the way in, and a request's {@code Origin} is rewritten to code-server's
 * own loopback origin: code-server compares {@code Origin} against its {@code Host}
 * (or {@code X-Forwarded-Host}) and refuses a mismatch, and seen from code-server the
 * only client is this engine on loopback. Redirects are never followed here — a
 * {@code Location} code-server sends is relative and belongs to the browser.
 */
@Component
public class CodeServerHttpProxy implements HttpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeServerHttpProxy.class);

    /**
     * Never forwarded upstream: hop-by-hop headers (RFC 9110 §7.6.1), the ones the
     * JDK client owns ({@code Host}, {@code Content-Length}, {@code Expect},
     * {@code Upgrade}, {@code Connection}), the outer reverse proxy's forwarding
     * headers, and {@code Origin} (rewritten, see the class comment).
     */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect",
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port",
            "x-forwarded-prefix", "x-real-ip", "origin");

    /** Never copied back: the container manages framing and connection lifetime itself. */
    private static final Set<String> NOT_RETURNED = Set.of(
            "connection", "keep-alive", "transfer-encoding", "upgrade", ":status");

    private final CodeServerProxyAuthorization authorization;
    private final HttpClient client;

    public CodeServerHttpProxy(CodeServerProxyAuthorization authorization) {
        this.authorization = authorization;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Optional<IdeProxyPath> parsed = IdeProxyPath.parse(request.getRequestURI());
        if (parsed.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        IdeProxyPath path = parsed.get();
        Principal principal = request.getUserPrincipal();
        Optional<URI> upstream = authorization.upstreamFor(path, principal == null ? null : principal.getName());
        if (upstream.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        if (path.rest() == null) {
            // ".../ide" with no trailing slash: code-server's pages link everything
            // relatively, and relative to ".../ide" those would resolve one segment too
            // high. A relative Location, set by hand rather than via sendRedirect, so an
            // outer TLS proxy's scheme is never guessed at from the Host header.
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", request.getRequestURI() + "/" + query);
            return;
        }
        URI target = URI.create(upstream.get() + path.rest() + query);
        HttpRequest forwarded = buildUpstreamRequest(request, target, upstream.get());
        try {
            HttpResponse<InputStream> upstreamResponse = client.send(forwarded, HttpResponse.BodyHandlers.ofInputStream());
            relay(upstreamResponse, response);
        } catch (IOException e) {
            log.warn("code-server at {} unreachable for {}", upstream.get(), request.getRequestURI(), e);
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while forwarding {} to code-server at {}", request.getRequestURI(), upstream.get(), e);
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
        }
    }

    private static HttpRequest buildUpstreamRequest(HttpServletRequest request, URI target, URI upstream) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        for (String name : Collections.list(request.getHeaderNames())) {
            if (NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                builder.header(name, value);
            }
        }
        if (request.getHeader("Origin") != null) {
            builder.header("Origin", upstream.toString());
        }
        HttpRequest.BodyPublisher body = hasBody(request)
                ? HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return request.getInputStream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                : HttpRequest.BodyPublishers.noBody();
        return builder.method(request.getMethod(), body).build();
    }

    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    private static void relay(HttpResponse<InputStream> upstreamResponse, HttpServletResponse response)
            throws IOException {
        response.setStatus(upstreamResponse.statusCode());
        upstreamResponse.headers().map().forEach((name, values) -> {
            if (NOT_RETURNED.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            values.forEach(value -> response.addHeader(name, value));
        });
        try (InputStream in = upstreamResponse.body(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }
}
