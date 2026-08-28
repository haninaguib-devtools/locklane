package dev.locklane.engine.github;

/**
 * One permanent GitHub release (#287): {@code release.yml} tags these {@code v<version>},
 * never the rolling {@code latest} pre-release.
 */
public record GhRelease(String tagName) {

    /** The tag with its leading {@code v} stripped, e.g. {@code "v0.2.0"} -> {@code "0.2.0"}. */
    public String version() {
        return tagName.startsWith("v") ? tagName.substring(1) : tagName;
    }
}
