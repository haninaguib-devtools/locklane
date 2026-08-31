package dev.locklane.engine.uploads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Writes files a browser dropped or pasted onto a console terminal (#436) to disk,
 * one folder per session under {@code locklane.uploads.dir}, so the client can hand
 * the CLI running in that session a real server-side path — the thing a browser
 * drop can never carry itself, since a page only ever gets the file's contents.
 *
 * <p>The per-session folder is the cleanup unit: {@link #deleteFor} removes it
 * recursively, and {@link dev.locklane.engine.pty.SessionRegistry#close} calls it
 * whenever a session ends for good — every closer (the per-tab close, the project
 * console close, the cleanup sweeps) already funnels through there. A disconnect or
 * engine restart is not an end: the session's record survives those, and so do its
 * uploads.
 */
@Service
public class SessionUploadStorage {

    private static final Logger log = LoggerFactory.getLogger(SessionUploadStorage.class);

    /** Kept short enough for any filesystem once the per-upload uniquifier is added. */
    private static final int MAX_FILENAME_LENGTH = 100;

    private final Path root;
    private final long maxFileBytes;

    public SessionUploadStorage(@Value("${locklane.uploads.dir}") String dir,
            @Value("${locklane.uploads.max-file-bytes}") long maxFileBytes) {
        this.root = Path.of(dir);
        this.maxFileBytes = maxFileBytes;
    }

    /** The configured refusal threshold — enforced by the controller, published here so the two agree. */
    public long maxFileBytes() {
        return maxFileBytes;
    }

    /**
     * Writes {@code content} under this session's folder and returns the absolute
     * path written. The stored name is derived from {@code originalFilename} —
     * sanitized down to a safe character set, since it is client-supplied — and
     * uniquified rather than overwritten when a name is uploaded twice: the CLI may
     * still be reading the first file when the second arrives.
     */
    public Path store(String sessionId, String originalFilename, InputStream content) throws IOException {
        Path directory = root.resolve(validatedSessionId(sessionId));
        Files.createDirectories(directory);
        String name = sanitizedFilename(originalFilename);
        for (int attempt = 0; ; attempt++) {
            Path target = directory.resolve(attempt == 0 ? name : uniquified(name, attempt));
            try {
                Files.copy(content, target);
                return target.toAbsolutePath().normalize();
            } catch (FileAlreadyExistsException e) {
                // Another upload holds this name; try the next suffix.
            }
        }
    }

    /**
     * Removes the session's upload folder and everything in it. A session that never
     * received an upload has no folder — that is a no-op, and so is a folder already
     * gone. A deletion failure is logged, never thrown: this runs inside session
     * close, which must not fail because a file was busy.
     */
    public void deleteFor(String sessionId) {
        Path directory = root.resolve(validatedSessionId(sessionId));
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(directory)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Failed to delete uploads for session {}", sessionId, e);
        }
    }

    /**
     * The session id becomes a directory name, so it must be a plain single path
     * segment. Every real id is (#43's "<projectId>-..." shape); anything else is a
     * request this storage refuses to touch the filesystem for.
     */
    private static String validatedSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\")
                || sessionId.equals(".") || sessionId.equals("..")) {
            throw new IllegalArgumentException("Invalid session id");
        }
        return sessionId;
    }

    /**
     * The client-supplied filename reduced to a safe single path segment: any
     * directory part is dropped, anything outside a conservative character set
     * becomes {@code _}, and a leading dot is stripped so no upload can hide itself
     * or spell a traversal. Truncation keeps the tail — that is where the extension
     * lives, and the extension is what tells the CLI it is looking at an image.
     */
    private static String sanitizedFilename(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[._]+", "");
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(name.length() - MAX_FILENAME_LENGTH);
        }
        return name.isEmpty() ? "upload" : name;
    }

    /** {@code shot.png} → {@code shot-1.png}; a name with no extension gets the suffix at the end. */
    private static String uniquified(String name, int attempt) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + "-" + attempt;
        }
        return name.substring(0, dot) + "-" + attempt + name.substring(dot);
    }
}
