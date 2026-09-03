# 656 — Renew short-lived device-flow GitHub tokens before they expire and on 401
Issue: #656

## Asked
A GitHub account connected through Settings → GitHub accounts → "Sign in with GitHub"
stops working about an hour after it is connected: every project on it shows
`HTTP 401: Bad credentials — never refreshed successfully` in the sidenav until a human
removes the account and signs in again. GitHub now issues short-lived, refreshable
tokens to OAuth Apps by default and the engine kept only the access token
(`docs/architecture/github-token-lifetime.md`, #620). Implement that document's §Fix:
persist the token pair, renew ahead of expiry and once on a 401, push the fresh token to
the project's cached `gh` client without a restart, and show an account that cannot be
renewed as needing reconnection.

## Done when
- Flyway migration `V17__AddRefreshTokenToGithubAccounts` adds nullable
  `refresh_token` (encrypted by `TokenCipher`), `token_expires_at`,
  `refresh_token_expires_at` and `renewal_failed_at` to `github_accounts`; the refresh
  token is never returned by `GhAccountsController` and never logged.
- `GhAccountsService.completeFlow` stores the pair and expiries when GitHub sent them;
  `addByToken` stores nulls and is never renewed.
- `GhDeviceFlow.refresh(clientId, refreshToken)` exists; `HttpGhDeviceFlow` POSTs
  `grant_type=refresh_token` to the access-token endpoint.
- A scheduled pass renews every account whose token expires within five minutes,
  stores the rotated pair, and evicts each referencing project's `gh` context once.
- A `Bad credentials` refresh triggers one renewal, an evict, and one retry; a second
  401 marks the account as needing reconnection and stops retrying.
- The accounts page shows such an account as needing reconnection.
- Tests with a fake `GhDeviceFlow`, no network; `./mvnw -B test` and the client tests pass.
- Human check: connect via the device flow, wait past the logged `expires_in`, no
  `Bad credentials`, sidenav still updates.

## Explicitly not
- Opting the shipped "Locklane" OAuth App out of expiring tokens in its GitHub settings
  (an owner-side interim measure, independent of this fix).
- Requesting `offline_access`, or re-running the device flow on a 401.
- Any change to the sidenav's per-project wording (#619).
- Adding the migration directory to `CONSTITUTION.md` §3 / `protected-paths.sh`
  (reserved to #238–#242).
- `GhIssueCache.java` and `CliGhClient.java` — named in the issue's Scope, excluded by
  the plan's Allowed paths: the 401 hook lives in `ProjectGhResources.refreshAll`.

## Decisions made along the way
- The renewal pass and the on-401 hook live in one new `GhTokenRenewalService`, so both
  paths share a per-account lock — GitHub rotates the refresh token on every use, and
  two concurrent renewals with the same refresh token would strand the account.
  (claude, 2026-09-03)
- `ProjectGhResources` does not depend on the renewal service (that would be a bean
  cycle: the service needs `evict`). Instead it exposes a small
  `CredentialRenewer` hook the renewal service registers itself on at construction;
  with no renewer registered a 401 is reported exactly as before. (claude, 2026-09-03)
- `needsReconnect` is derived, not stored: `renewal_failed_at` is set, or the refresh
  token's own expiry has passed. A successful renewal clears `renewal_failed_at`.
  (claude, 2026-09-03)

## Deviations / notes
- `docs/architecture/` is protected: `/t-review` is required before shipping.
- Three client spec files outside the plan's Allowed paths were edited:
  `client/src/app/components/add-project-popup/add-project-popup.component.spec.ts`,
  `client/src/app/services/github-accounts.service.spec.ts`,
  `client/src/app/services/projects.service.spec.ts`. Each edit only adds
  `needsReconnect: false, tokenExpiresAt: null` to a hard-coded `GithubAccount`
  fixture so the client still type-checks after the interface (which the plan did
  allow) gained two required fields — the client-side twin of the "existing tests
  adjusted for the wider shape" the plan foresaw for the engine. No behaviour or
  assertion changed. Raised by the cold review on PR #657 (high, scope); recorded here
  in the fix pass rather than widening the plan, which is a tracker write the human
  may ask for separately. (claude, 2026-09-03)
