package dev.locklane.engine.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Backstop for any exception that reaches a controller without being caught by one of
 * its own narrower {@code @ExceptionHandler}s (#546). Without this, an unhandled
 * exception fell through to the servlet container's own default handling, which logs
 * with no application context.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than declaring a bare
 * {@code @ExceptionHandler(Exception.class)}: that broader form intercepted every
 * framework-level exception too — a malformed request body ({@code
 * HttpMessageNotReadableException}, normally 400), the wrong HTTP verb ({@code
 * HttpRequestMethodNotSupportedException}, normally 405), and others — turning them
 * all into a 500, a real change to what the API tells its callers on request shapes
 * any client can trigger by accident, not just a previously-unhandled failure
 * (confirmed empirically against a live instance during review). {@link
 * ResponseEntityExceptionHandler} already maps each of those well-known exception
 * types to its own correct status; {@link #handleExceptionInternal} below is the one
 * hook all of them funnel through, so logging is added there instead of widening what
 * gets caught.
 *
 * <p>{@link NoResourceFoundException} is the one exception type left unanswered —
 * {@link #handleNoResourceFoundException} below rethrows it rather than building a
 * response, since it is how Spring signals an unmapped path, which {@link
 * dev.locklane.engine.SpaFallbackController} depends on reaching Boot's own {@code
 * /error} dispatch to decide between the SPA shell and a genuine 404.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The one truly generic backstop: anything not already one of Spring's own well-known MVC exceptions. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> onUnhandledException(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
    }

    /**
     * Every exception {@link ResponseEntityExceptionHandler}'s own built-in handlers
     * resolve (malformed body, wrong verb, unsupported media type, a missing/malformed
     * request parameter, and the rest) funnels through here with its status already
     * decided correctly — this only adds the log line, never changes {@code
     * statusCode}. A second, explicit {@code @ExceptionHandler} method for one of
     * those types is not an option here: {@code ResponseEntityExceptionHandler}'s own
     * {@code handleException} is {@code final} and already lists every one of them,
     * so a second method claiming any of the same types is an ambiguous mapping
     * Spring refuses to start with (confirmed empirically fixing this exact class).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        log.error("Handled exception ({}) on {} {}", statusCode.value(), servletRequest.getMethod(),
                servletRequest.getRequestURI(), e);
        return super.handleExceptionInternal(e, body, headers, statusCode, request);
    }

    /**
     * {@link ResponseEntityExceptionHandler}'s own dedicated hook for this one
     * exception type — overridden instead of handling it generically because it must
     * not be <em>answered</em> at all: {@code NoResourceFoundException} is how Spring
     * signals an unmapped path, which {@link
     * dev.locklane.engine.SpaFallbackController} depends on reaching Boot's own {@code
     * /error} dispatch to decide between the SPA shell and a genuine 404 — building a
     * response here (the default this method otherwise would) would turn every
     * unmapped Angular route into whatever this returns instead. Rethrowing the exact
     * same instance is what Spring's own exception-resolver chain treats as "this
     * resolver declines" (confirmed empirically, and how the equivalent code worked
     * before this override point existed): the next resolver down the chain
     * ({@code DefaultHandlerExceptionResolver}) still gives it its correct 404, and
     * an unmapped path still reaches {@code SpaFallbackController}. The rethrow uses
     * an unchecked bridge only because this override's inherited signature — {@code
     * NoResourceFoundException} extends the checked {@code jakarta.servlet.
     * ServletException} — declares no checked exception, not because anything here
     * is actually unchecked.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException e, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        return sneakyRethrow(e);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyRethrow(Throwable t) throws T {
        throw (T) t;
    }
}
