package dev.locklane.engine.github;

/**
 * GitHub's OAuth device flow (#550) — the two calls a "Sign in with GitHub" button
 * makes: start a flow to get a user code and a link to show the operator, then poll
 * until they've approved it (or it expired, or they denied it). An interface so
 * tests substitute a fake instead of ever reaching the real {@code github.com}
 * endpoints; {@link HttpGhDeviceFlow} is the real implementation.
 */
public interface GhDeviceFlow {

    /** Starts a device flow for {@code clientId}, requesting {@code scope} (space-separated). */
    DeviceCode start(String clientId, String scope);

    /** One poll of the token endpoint for a flow already started with {@link #start}. */
    PollResult poll(String clientId, String deviceCode);

    /** What {@link #start} returns to show the operator, and what {@link #poll} needs from then on. */
    record DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresInSeconds,
            int intervalSeconds) {
    }

    /** One outcome of a single {@link #poll} call. */
    sealed interface PollResult permits PollResult.Success, PollResult.Pending, PollResult.SlowDown,
            PollResult.Expired, PollResult.Denied, PollResult.Error {

        /**
         * The operator approved it — {@code accessToken} is ready to store. The rest is
         * the whole of GitHub's token response (#620): {@code tokenType} and
         * {@code scope} always; {@code expiresInSeconds}, {@code refreshToken} and
         * {@code refreshTokenExpiresInSeconds} only when the OAuth App issues
         * short-lived tokens (GitHub's default for every app registered since
         * 2026-08-14), {@code null} otherwise. Carried so the engine can see — and
         * log, redacted — which shape it was handed, instead of silently keeping only
         * the access token and finding out an hour later.
         */
        record Success(String accessToken, String tokenType, String scope, Integer expiresInSeconds,
                String refreshToken, Integer refreshTokenExpiresInSeconds) implements PollResult {

            /** A non-expiring token: the shape every response had before short-lived tokens existed. */
            public Success(String accessToken) {
                this(accessToken, "bearer", "", null, null, null);
            }

            /** True when GitHub said this token expires — {@code expiresInSeconds} and, normally, {@code refreshToken} are set. */
            public boolean expires() {
                return expiresInSeconds != null;
            }
        }

        /** Not approved yet — poll again after the flow's interval. */
        record Pending() implements PollResult {
        }

        /** Polled too fast — back off by 5 more seconds, per GitHub's device flow spec. */
        record SlowDown() implements PollResult {
        }

        /** The user code expired before the operator approved it. */
        record Expired() implements PollResult {
        }

        /** The operator explicitly declined the request on GitHub's own page. */
        record Denied() implements PollResult {
        }

        /** Anything else — unreachable host, an error GitHub's own device flow doesn't define. */
        record Error(String message) implements PollResult {
        }
    }
}
