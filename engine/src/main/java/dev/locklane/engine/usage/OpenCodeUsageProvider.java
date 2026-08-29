package dev.locklane.engine.usage;

import java.util.Optional;

/**
 * OpenCode's usage (#295) — structurally mirrors {@link ClaudeUsageProvider}/
 * {@link CodexUsageProvider} (its own token source, wired into the same
 * {@link UsageService}), but with a real gap those two don't have: OpenCode's account
 * model carries no five-hour/weekly percent-of-window quota the way a Claude or ChatGPT
 * subscription does. Its Zen billing is a pay-as-you-go dollar balance with no
 * time-based reset (confirmed from OpenCode's own docs), and no documented or
 * discoverable balance-check endpoint exists to call — unlike Claude/Codex, whose
 * providers were reverse-engineered against a live, logged-in installation, no such
 * account was available here to verify one safely.
 *
 * <p>{@link #fetch()} therefore always degrades to {@link ProviderUsage#unavailable()},
 * even with valid credentials — the honest representation of "no window-shaped quota
 * exists to show" rather than a fabricated call to an unverified endpoint. No
 * {@link UsageHttpClient} is taken here (unlike Claude/Codex) because nothing is called
 * yet; a real endpoint, if OpenCode ever publishes one, adds that dependency alongside
 * filling in this method's body.
 */
public class OpenCodeUsageProvider implements UsageProvider {

    private final OpenCodeTokenSource tokenSource;

    public OpenCodeUsageProvider(OpenCodeTokenSource tokenSource) {
        this.tokenSource = tokenSource;
    }

    @Override
    public ProviderUsage fetch() {
        Optional<OpenCodeCredentials> credentials = tokenSource.credentials();
        if (credentials.isEmpty()) {
            return ProviderUsage.unavailable();
        }
        // Credentials exist, but no verified endpoint to call yet — see the class doc.
        return ProviderUsage.unavailable();
    }
}
