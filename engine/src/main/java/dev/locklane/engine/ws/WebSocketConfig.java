package dev.locklane.engine.ws;

import dev.locklane.engine.pty.SessionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionRegistry sessionRegistry;
    private final String[] allowedOrigins;

    public WebSocketConfig(SessionRegistry sessionRegistry,
            @Value("${locklane.security.allowed-origins}") String allowedOrigins) {
        this.sessionRegistry = sessionRegistry;
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Authentication is required upstream (SecurityConfig, #50) — this is only
        // the origin restriction half of that task.
        registry.addHandler(new TerminalWebSocketHandler(sessionRegistry), "/ws/sessions/*")
                .setAllowedOrigins(allowedOrigins);
    }
}
