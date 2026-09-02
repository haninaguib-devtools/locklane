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

        /** The operator approved it — {@code accessToken} is ready to store. */
        record Success(String accessToken) implements PollResult {
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
