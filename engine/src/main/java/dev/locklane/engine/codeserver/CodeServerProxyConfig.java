package dev.locklane.engine.codeserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Maps the proxied-IDE path family (#655), {@code /api/projects/{projectId}/consoles/{id}/ide/**},
 * onto its two handlers: a WebSocket upgrade goes to {@link CodeServerWebSocketProxy},
 * everything else to {@link CodeServerHttpProxy}. Two handler mappings of its own,
 * rather than a {@code @RequestMapping} controller plus the shared registry in
 * {@code WebSocketConfig}, because ordering is what makes the split work:
 *
 * <ul>
 * <li>The WebSocket mapping sits at order 1 with {@code webSocketUpgradeMatch} on, so
 * it claims only {@code GET} requests carrying {@code Upgrade: websocket}. The shared
 * registry's mapping leaves that flag off — it would swallow every plain request on
 * the path too, answering a page load with its origin check instead of a proxy.</li>
 * <li>The HTTP mapping sits at order 2, behind Spring MVC's controller mapping (0)
 * and the WebSocket one (1), so it sees exactly the requests that are not
 * upgrades.</li>
 * </ul>
 *
 * The WebSocket side keeps the same {@code locklane.security.allowed-origins}
 * restriction as the terminal socket: whoever may attach a console may open its IDE,
 * and no other origin gains anything new. Authentication itself is enforced upstream
 * of both mappings, in {@code SecurityConfig}.
 */
@Configuration
public class CodeServerProxyConfig {

    static final String IDE_PATHS = "/api/projects/*/consoles/*/ide/**";
    static final String IDE_ROOT = "/api/projects/*/consoles/*/ide";

    @Bean
    public WebSocketHandlerMapping codeServerWebSocketHandlerMapping(CodeServerWebSocketProxy proxy,
            @Value("${locklane.security.allowed-origins}") String allowedOrigins) {
        WebSocketHttpRequestHandler handshake = new WebSocketHttpRequestHandler(proxy, new DefaultHandshakeHandler());
        handshake.setHandshakeInterceptors(
                List.of(new OriginHandshakeInterceptor(Arrays.asList(allowedOrigins.split(",")))));
        WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
        mapping.setUrlMap(Map.of(IDE_PATHS, handshake));
        mapping.setWebSocketUpgradeMatch(true);
        mapping.setOrder(1);
        return mapping;
    }

    @Bean
    public SimpleUrlHandlerMapping codeServerHttpProxyHandlerMapping(CodeServerHttpProxy proxy) {
        return new SimpleUrlHandlerMapping(Map.of(IDE_ROOT, proxy, IDE_PATHS, proxy), 2);
    }
}
