package dev.locklane.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Boot forwards every unmapped request to {@code /error} by default -- including an
 * Angular client-side route like {@code /projects/42/issues/7} (#161), which has no
 * server-side mapping of its own, so a browser refresh or direct link would otherwise
 * hit the whitelabel error page instead of the SPA. Forward those to {@code index.html}
 * so the client router takes over; {@code /api} and {@code /ws} paths are genuine 404s
 * and keep the ordinary JSON error response. This generalizes to any future top-level
 * Angular route with no server-side edit. Implementing {@link ErrorController} replaces
 * Boot's default {@code BasicErrorController} (its auto-configuration backs off once any
 * {@code ErrorController} bean exists), so the {@code /api}/{@code /ws} branch below
 * reproduces its JSON error body directly instead of delegating to it.
 */
@Controller
public class SpaFallbackController implements ErrorController {

    private final ErrorAttributes errorAttributes;
    private final ObjectMapper objectMapper;

    public SpaFallbackController(ErrorAttributes errorAttributes, ObjectMapper objectMapper) {
        this.errorAttributes = errorAttributes;
        this.objectMapper = objectMapper;
    }

    @RequestMapping("/error")
    public void handleError(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Object requestedPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (requestedPath instanceof String path && !path.startsWith("/api") && !path.startsWith("/ws")) {
            // A forward doesn't reset the status the container already set for the error
            // dispatch (404) -- without this, the SPA shell would be served under a 404.
            response.setStatus(HttpServletResponse.SC_OK);
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        Map<String, Object> body = errorAttributes.getErrorAttributes(
                new ServletWebRequest(request, response), ErrorAttributeOptions.defaults());
        response.setStatus((int) body.getOrDefault("status", 500));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
