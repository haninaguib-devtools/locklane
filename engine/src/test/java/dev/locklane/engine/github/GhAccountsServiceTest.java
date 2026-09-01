package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** #532: parsing of {@code gh auth status --json hosts}, over stubbed output — the real CLI is never run here. */
class GhAccountsServiceTest {

    /** The reference shape from the issue, as gh 2.98.0 prints it (extra fields trimmed to the ones that matter). */
    private static final String TWO_ACCOUNTS = """
            {"hosts":{"github.com":[
              {"active":true,"host":"github.com","login":"haninaguib","tokenSource":"keyring"},
              {"active":false,"host":"github.com","login":"hani-thyme","tokenSource":"keyring"}
            ]}}
            """;

    @Test
    void listsTheGithubComLoginsInGhsOrderWithTheActiveFlag() {
        GhAccountsService service = new GhAccountsService(() -> Optional.of(TWO_ACCOUNTS));

        assertThat(service.accounts()).containsExactly(
                new GhAccount("haninaguib", true),
                new GhAccount("hani-thyme", false));
    }

    @Test
    void aSingleLoginIsStillListed() {
        GhAccountsService service = new GhAccountsService(() -> Optional.of(
                "{\"hosts\":{\"github.com\":[{\"active\":true,\"host\":\"github.com\",\"login\":\"solo\"}]}}"));

        assertThat(service.accounts()).containsExactly(new GhAccount("solo", true));
    }

    @Test
    void noAccountsAtAllIsAnEmptyList() {
        // Exactly what gh prints (exit 0) when nobody has run `gh auth login` on the host.
        GhAccountsService service = new GhAccountsService(() -> Optional.of("{\"hosts\":{}}"));

        assertThat(service.accounts()).isEmpty();
    }

    @Test
    void onlyTheGithubComHostIsListed() {
        GhAccountsService service = new GhAccountsService(() -> Optional.of("""
                {"hosts":{"ghe.example.com":[{"active":true,"host":"ghe.example.com","login":"enterprise-only"}]}}
                """));

        assertThat(service.accounts()).isEmpty();
    }

    @Test
    void ghNotRunnableIsAnEmptyListNotAnError() {
        GhAccountsService service = new GhAccountsService(Optional::empty);

        assertThat(service.accounts()).isEmpty();
    }

    @Test
    void unparseableOutputIsAnEmptyListNotAnError() {
        GhAccountsService service = new GhAccountsService(() -> Optional.of("not json at all"));

        assertThat(service.accounts()).isEmpty();
    }

    @Test
    void anAccountWithoutALoginIsSkipped() {
        GhAccountsService service = new GhAccountsService(() -> Optional.of(
                "{\"hosts\":{\"github.com\":[{\"active\":true},{\"active\":false,\"login\":\"named\"}]}}"));

        assertThat(service.accounts()).containsExactly(new GhAccount("named", false));
    }
}
