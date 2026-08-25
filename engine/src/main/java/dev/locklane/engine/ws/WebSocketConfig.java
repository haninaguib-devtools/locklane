package dev.locklane.engine.ws;

import dev.locklane.engine.pty.SessionRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionRegistry sessionRegistry;

    public WebSocketConfig(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Allowed origins are wide open: there is no client yet (#3) and no auth
        // layer (out of scope here — flagged in this task's record as worth a
        // dedicated follow-up before this is ever exposed off localhost).
        registry.addHandler(new TerminalWebSocketHandler(sessionRegistry), "/ws/sessions/*")
                .setAllowedOrigins("*");
    }
}
