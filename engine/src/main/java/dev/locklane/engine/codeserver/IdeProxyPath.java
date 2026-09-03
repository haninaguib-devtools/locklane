package dev.locklane.engine.codeserver;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one parser for the proxied-IDE path family (#655),
 * {@code /api/projects/{projectId}/consoles/{id}/ide[/<rest>]}, shared by the HTTP
 * and WebSocket proxies so the two can never disagree about which console a request
 * names. {@code rest} is the raw (still percent-encoded) remainder from its leading
 * slash, or {@code null} when the request stopped at {@code /ide} with no slash — the
 * HTTP proxy redirects that form to the slash-terminated one, since code-server's
 * pages use relative URLs that must resolve inside the prefix.
 */
record IdeProxyPath(long projectId, String consoleId, String rest) {

    private static final Pattern PATH = Pattern.compile("^/api/projects/(\\d+)/consoles/([^/]+)/ide(/.*)?$");

    /** Parses a raw request path; empty for anything outside the family. */
    static Optional<IdeProxyPath> parse(String rawPath) {
        if (rawPath == null) {
            return Optional.empty();
        }
        Matcher matcher = PATH.matcher(rawPath);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new IdeProxyPath(Long.parseLong(matcher.group(1)), matcher.group(2), matcher.group(3)));
    }
}
