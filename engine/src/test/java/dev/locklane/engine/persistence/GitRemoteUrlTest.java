package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** #551: every accepted import-URL shape normalizes to one plain HTTPS form; anything else is rejected. */
class GitRemoteUrlTest {

    private static final String NORMALIZED = "https://github.com/foo/bar.git";

    @Test
    void normalizesAnHttpsGithubUrl() {
        assertThat(GitRemoteUrl.normalize("https://github.com/foo/bar")).contains(NORMALIZED);
        assertThat(GitRemoteUrl.normalize("https://github.com/foo/bar.git")).contains(NORMALIZED);
        assertThat(GitRemoteUrl.normalize("https://github.com/foo/bar.git/")).contains(NORMALIZED);
    }

    @Test
    void normalizesAGithubSshUrl() {
        assertThat(GitRemoteUrl.normalize("git@github.com:foo/bar")).contains(NORMALIZED);
        assertThat(GitRemoteUrl.normalize("git@github.com:foo/bar.git")).contains(NORMALIZED);
    }

    @Test
    void normalizesAnSshAliasUrlOnlyTheOwnerRepoPathMatters() {
        // A locally-configured SSH host alias (e.g. Host thyme.github.com in
        // ~/.ssh/config, pointing at github.com under a different key) -- accounts
        // are github.com only, so the alias itself is never inspected.
        assertThat(GitRemoteUrl.normalize("git@thyme.github.com:foo/bar.git")).contains(NORMALIZED);
        assertThat(GitRemoteUrl.normalize("git@work-alias:foo/bar")).contains(NORMALIZED);
    }

    @Test
    void normalizesABareOwnerRepo() {
        assertThat(GitRemoteUrl.normalize("foo/bar")).contains(NORMALIZED);
        assertThat(GitRemoteUrl.normalize("foo/bar.git")).contains(NORMALIZED);
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(GitRemoteUrl.normalize("  foo/bar  ")).contains(NORMALIZED);
    }

    @Test
    void rejectsAnyOtherHost() {
        assertThat(GitRemoteUrl.normalize("https://gitlab.com/foo/bar.git")).isEmpty();
        assertThat(GitRemoteUrl.normalize("https://example.com/foo/bar")).isEmpty();
    }

    @Test
    void rejectsPlainHttp() {
        assertThat(GitRemoteUrl.normalize("http://github.com/foo/bar.git")).isEmpty();
    }

    @Test
    void rejectsAMalformedPath() {
        assertThat(GitRemoteUrl.normalize("https://github.com/just-an-owner")).isEmpty();
        assertThat(GitRemoteUrl.normalize("https://github.com/foo/bar/extra")).isEmpty();
        assertThat(GitRemoteUrl.normalize("just-an-owner")).isEmpty();
    }

    @Test
    void rejectsBlankOrNull() {
        assertThat(GitRemoteUrl.normalize("")).isEmpty();
        assertThat(GitRemoteUrl.normalize("   ")).isEmpty();
        assertThat(GitRemoteUrl.normalize(null)).isEmpty();
    }

    @Test
    void rejectsALocalPath() {
        // The pre-#551 shape a bad or non-GitHub input used to be stored as verbatim.
        assertThat(GitRemoteUrl.normalize("/does/not/exist")).isEmpty();
    }

    @Test
    void resultIsAlwaysExactlyPreformedRegardlessOfInputCase() {
        Optional<String> normalized = GitRemoteUrl.normalize("https://github.com/Foo/Bar.git");
        // GitHub owner/repo names are case-preserving in the URL itself -- this class
        // never lowercases them, only reshapes the surrounding URL.
        assertThat(normalized).contains("https://github.com/Foo/Bar.git");
    }
}
