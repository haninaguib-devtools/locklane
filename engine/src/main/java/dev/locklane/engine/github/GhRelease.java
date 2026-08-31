package dev.locklane.engine.github;

/**
 * One permanent GitHub release (#287): {@code release.yml} tags these {@code v<version>},
 * never the rolling {@code latest} pre-release. {@code url} is the release's own
 * Releases-page address as GitHub reports it
 * ({@code https://github.com/<owner>/<repo>/releases/tag/<tag>}, #466) — carried rather
 * than assembled here, so no tag-to-URL mapping is assumed.
 */
public record GhRelease(String tagName, String url) {

    /** The tag with its leading {@code v} stripped, e.g. {@code "v0.2.0"} -> {@code "0.2.0"}. */
    public String version() {
        return tagName.startsWith("v") ? tagName.substring(1) : tagName;
    }
}
