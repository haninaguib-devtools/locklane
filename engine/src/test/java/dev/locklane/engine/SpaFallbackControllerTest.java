package dev.locklane.engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser refresh (or a fresh GET request) on a non-root Angular route like
 * {@code /projects/42/issues/7} has no server-side mapping of its own (#161) --
 * confirms it reaches the real, unmapped-route 404 path (only exercised with an actual
 * embedded server, hence {@code RANDOM_PORT} rather than {@code MockMvc}) and comes back
 * as the SPA shell, not a 404, while root and API routes behave as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpaFallbackControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void reloadingADeepAngularRouteServesTheSpaShell() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/projects/42/issues/7", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<app-root>");
    }

    @Test
    void reloadingAnotherNonRootRouteAlsoServesTheSpaShell() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/projects/42/issues", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<app-root>");
    }

    @Test
    void rootStillServesTheSpaShell() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<app-root>");
    }

    @Test
    void anUnmappedApiPathStillReturnsNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
