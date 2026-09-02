package dev.locklane.engine.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Backstop for any exception that reaches a controller without being caught by one of
 * its own narrower {@code @ExceptionHandler}s (#546). Without this, an unhandled
 * exception fell through to the servlet container's own default handling, which logs
 * with no application context — a client still got the same 500 either way, so this
 * changes only whether the cause is diagnosable afterward, never the status returned.
 *
 * <p>{@link NoResourceFoundException} is rethrown rather than handled here: it is how
 * Spring signals an unmapped path, which {@link dev.locklane.engine.SpaFallbackController}
 * depends on reaching Boot's own {@code /error} dispatch to decide between the SPA
 * shell and a genuine 404 — handling it here would turn every unmapped Angular route
 * into a 500 instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnhandledException(Exception e, HttpServletRequest request)
            throws Exception {
        if (e instanceof NoResourceFoundException) {
            throw e;
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
    }
}
