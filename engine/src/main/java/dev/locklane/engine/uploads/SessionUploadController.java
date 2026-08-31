package dev.locklane.engine.uploads;

import dev.locklane.engine.persistence.WorktreeSessionAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Map;

/**
 * Receives a file the browser dropped or pasted onto a console terminal (#436) and
 * returns the server-side path {@link SessionUploadStorage} wrote it to, for the
 * client to inject into the PTY as a bracketed paste — to the CLI it then looks
 * exactly like a path dragged into a native terminal.
 *
 * <p>Authorized like every other way of reaching a session ({@code
 * TerminalWebSocketHandler}, the REST listings): the caller must own the session's
 * project, per ADR-105, decided by the same shared
 * {@link WorktreeSessionAuthorization} so the answers can never drift. A session
 * the caller may not see is 404 — the same shape {@code
 * WorktreeController#closeSession} gives, revealing nothing about whether the id
 * exists. Authentication itself is enforced upstream: {@code SecurityConfig} lists
 * {@code /api/sessions/*&#47;uploads} among its authenticated matchers, which
 * matters because that config ends in {@code permitAll}.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/uploads")
public class SessionUploadController {

    private final SessionUploadStorage storage;
    private final WorktreeSessionAuthorization authorization;

    public SessionUploadController(SessionUploadStorage storage, WorktreeSessionAuthorization authorization) {
        this.storage = storage;
        this.authorization = authorization;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> upload(@PathVariable String sessionId,
            @RequestParam("file") MultipartFile file, Principal principal) {
        if (!authorization.isVisibleTo(sessionId, principal.getName())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such session"));
        }
        if (file.getSize() > storage.maxFileBytes()) {
            return tooLargeResponse();
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "The upload is empty — folders and empty files cannot be uploaded"));
        }
        try (InputStream content = file.getInputStream()) {
            Path stored = storage.store(sessionId, file.getOriginalFilename(), content);
            return ResponseEntity.ok(Map.of("path", stored.toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to store the upload: " + e.getMessage()));
        }
    }

    /**
     * The servlet container's own multipart ceiling ({@code
     * spring.servlet.multipart.max-file-size}, set above the cap) rejects a body too
     * big to be worth reading before the handler runs — mapped to the same clear 413
     * the in-handler cap check produces, instead of a generic 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> maxUploadSizeExceeded() {
        return tooLargeResponse();
    }

    private ResponseEntity<Map<String, String>> tooLargeResponse() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "The file exceeds the " + humanReadableCap() + " upload limit"));
    }

    private String humanReadableCap() {
        long cap = storage.maxFileBytes();
        return cap >= 1 << 20 ? (cap >> 20) + " MB" : cap + " bytes";
    }
}
