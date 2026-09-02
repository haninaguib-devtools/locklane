package dev.locklane.engine.persistence;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes an import URL to {@code https://github.com/<owner>/<repo>.git} before it
 * is ever stored (#551): a project's git transport used to follow whatever URL was
 * pasted at import, so a repository imported over an SSH alias stayed on SSH forever,
 * tied to whatever key and {@code ~/.ssh/config} entry happened to exist on the engine
 * host. Accounts are {@code github.com} only (ADR — #549's own boundary), so this is
 * always the one normal form every accepted input collapses to.
 *
 * <p>Four shapes are accepted, each keeping only the {@code owner/repo} path: an
 * {@code https://github.com/...} URL, {@code git@github.com:owner/repo}, {@code
 * git@<any-alias>:owner/repo} (the host portion before the colon is never checked —
 * it exists only so an SSH config alias can resolve to {@code github.com} without
 * this class needing to know that alias), and a bare {@code owner/repo}. Anything else
 * — any other host, a malformed path — is rejected.
 */
public final class GitRemoteUrl {

    private static final Pattern HTTPS_GITHUB =
            Pattern.compile("^https://github\\.com/([^/\\s]+)/([^/\\s]+?)(\\.git)?/?$");
    private static final Pattern SSH_ALIAS = Pattern.compile("^git@[^:\\s]+:([^/\\s]+)/([^/\\s]+?)(\\.git)?$");
    private static final Pattern BARE_OWNER_REPO = Pattern.compile("^([^/\\s]+)/([^/\\s]+?)(\\.git)?$");

    private GitRemoteUrl() {
    }

    /** Empty for anything that is not one of the four accepted shapes. */
    public static Optional<String> normalize(String rawUrl) {
        if (rawUrl == null) {
            return Optional.empty();
        }
        String trimmed = rawUrl.strip();
        for (Pattern pattern : new Pattern[] {HTTPS_GITHUB, SSH_ALIAS, BARE_OWNER_REPO}) {
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.matches()) {
                return Optional.of("https://github.com/" + matcher.group(1) + "/" + matcher.group(2) + ".git");
            }
        }
        return Optional.empty();
    }
}
