package dev.locklane.engine.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GlobalExceptionHandler} is the backstop every unhandled controller exception
 * now reaches (#546) — asserts it logs at ERROR with the request's method and path,
 * and that it rethrows {@link NoResourceFoundException} rather than swallowing it,
 * since {@code SpaFallbackControllerTest} depends on that exception still reaching
 * Boot's own {@code /error} dispatch.
 */
class GlobalExceptionHandlerTest {

    @Test
    void logsTheUnhandledExceptionAtErrorWithTheRequestMethodAndPath() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects/1/issues/7/worktrees");
        RuntimeException boom = new RuntimeException("boom");

        List<ILoggingEvent> events = capturingLogs(() -> {
            var response = handler.onUnhandledException(boom, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        });

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("POST").contains("/api/projects/1/issues/7/worktrees");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
        });
    }

    @Test
    void rethrowsNoResourceFoundExceptionRatherThanHandlingIt() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/projects/42/issues/7");
        NoResourceFoundException notFound =
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/projects/42/issues/7");

        assertThatThrownBy(() -> handler.onUnhandledException(notFound, request)).isSameAs(notFound);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static List<ILoggingEvent> capturingLogs(ThrowingRunnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
