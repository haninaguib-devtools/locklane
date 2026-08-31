package dev.locklane.engine.ws;

import dev.locklane.engine.github.ReleaseUpdateChecker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final EventBroadcaster eventBroadcaster;
    private final ReleaseUpdateChecker releaseUpdateChecker;
    private final String[] allowedOrigins;
    // The events channel's version stamp (#273): BuildProperties#getTime() is stamped
    // at Maven build time (see engine/pom.xml's build-info execution), so it differs
    // between any two builds, including two builds of the same commit.
    private final String versionStamp;
    // The human-readable version this build was made as (#467): the Maven project
    // version, e.g. 0.1.0-SNAPSHOT on a dev build, 0.1.0 on a release build.
    private final String runningVersion;

    public WebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler, EventBroadcaster eventBroadcaster,
            ReleaseUpdateChecker releaseUpdateChecker,
            @Value("${locklane.security.allowed-origins}") String allowedOrigins, BuildProperties buildProperties) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
        this.eventBroadcaster = eventBroadcaster;
        this.releaseUpdateChecker = releaseUpdateChecker;
        this.allowedOrigins = allowedOrigins.split(",");
        this.versionStamp = buildProperties.getTime().toString();
        this.runningVersion = buildProperties.getVersion();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Authentication is required upstream (SecurityConfig, #50) — this is only
        // the origin restriction half of that task.
        registry.addHandler(terminalWebSocketHandler, "/ws/sessions/*")
                .setAllowedOrigins(allowedOrigins);
        // The app-wide notification channel (#128), separate from the per-session
        // terminal sockets above.
        registry.addHandler(new EventsWebSocketHandler(eventBroadcaster, versionStamp, runningVersion,
                        releaseUpdateChecker::newerReleaseAvailable), "/ws/events")
                .setAllowedOrigins(allowedOrigins);
    }
}
