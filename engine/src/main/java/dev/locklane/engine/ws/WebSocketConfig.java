package dev.locklane.engine.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    // A Spring-managed bean since #665 (unlike before, when this class built it by
    // hand): its own @Scheduled heartbeat tick only runs if Spring's scheduler
    // actually owns the bean.
    private final EventsWebSocketHandler eventsWebSocketHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler,
            EventsWebSocketHandler eventsWebSocketHandler,
            @Value("${locklane.security.allowed-origins}") String allowedOrigins) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
        this.eventsWebSocketHandler = eventsWebSocketHandler;
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Authentication is required upstream (SecurityConfig, #50) — this is only
        // the origin restriction half of that task.
        registry.addHandler(terminalWebSocketHandler, "/ws/sessions/*")
                .setAllowedOrigins(allowedOrigins);
        // The app-wide notification channel (#128), separate from the per-session
        // terminal sockets above.
        registry.addHandler(eventsWebSocketHandler, "/ws/events")
                .setAllowedOrigins(allowedOrigins);
    }
}
