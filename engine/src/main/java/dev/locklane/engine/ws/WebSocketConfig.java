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
    private final EventBroadcaster eventBroadcaster;
    private final String[] allowedOrigins;

    public WebSocketConfig(SessionRegistry sessionRegistry, EventBroadcaster eventBroadcaster,
            @Value("${locklane.security.allowed-origins}") String allowedOrigins) {
        this.sessionRegistry = sessionRegistry;
        this.eventBroadcaster = eventBroadcaster;
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Authentication is required upstream (SecurityConfig, #50) — this is only
        // the origin restriction half of that task.
        registry.addHandler(new TerminalWebSocketHandler(sessionRegistry), "/ws/sessions/*")
                .setAllowedOrigins(allowedOrigins);
        // The app-wide notification channel (#128), separate from the per-session
        // terminal sockets above.
        registry.addHandler(new EventsWebSocketHandler(eventBroadcaster), "/ws/events")
                .setAllowedOrigins(allowedOrigins);
    }
}
