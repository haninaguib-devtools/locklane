package dev.locklane.engine.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link GlobalExceptionHandler} does not change the status code a well-formed
 * framework-level failure already got before it existed (#546) — exercised through the
 * real dispatch stack, not by calling its handler methods directly, since that
 * (deliberately) bypasses Spring's own handler-selection and would miss exactly the
 * regression a review caught: a global {@code @ExceptionHandler(Exception.class)}
 * intercepting {@code HttpMessageNotReadableException}/{@code
 * HttpRequestMethodNotSupportedException} ahead of Spring's own handling for them,
 * turning a normal 400/405 into a 500. {@code /api/auth/2fa/verify} is used because
 * {@code SecurityConfig} leaves it unauthenticated and it is mapped {@code POST} only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GlobalExceptionHandlerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aMalformedRequestBodyStillAnswers400NotAnUnhandled500() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{ not valid json", headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(baseUrl() + "/api/auth/2fa/verify", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theWrongHttpMethodStillAnswers405NotAnUnhandled500() {
        ResponseEntity<String> response =
                restTemplate.exchange(baseUrl() + "/api/auth/2fa/verify", HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
