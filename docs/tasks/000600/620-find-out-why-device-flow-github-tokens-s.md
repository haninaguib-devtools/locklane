# 620 — Find out why device-flow GitHub tokens stop working after 60 minutes and keep them valid
Issue: #620 · Part of: #618

## Asked
GitHub accounts connected through the device flow stop working a fixed interval after
they are connected (observed: 60 minutes, two accounts, each on its own clock), after
which every `gh issue list` for their projects fails with `HTTP 401: Bad credentials`.
The engine stores only `access_token` and never checks or renews it. Establish the
cause with evidence before choosing a fix, write the design under `docs/architecture/`,
and ship the fix in this task only if it is small and unprotected; if it needs a schema
migration or a new engine secret, stop after the design and split the implementation.

## Done when
- The cause is written down with evidence, including the full field set GitHub returns
  on the device-flow token response (values redacted) and, if not expiry, what revokes
  the token.
- If GitHub returns `refresh_token`/`expires_in`: the engine persists them encrypted,
  renews before expiry and on a 401, evicts every affected project's `gh` context, with
  a fake-`GhDeviceFlow` unit test; pasted-token accounts untouched.
- If revoked rather than expiring: the design names the fix, shipped or split.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.
- Human check: connect via device flow, wait 90 minutes, no `Bad credentials`, sidenav
  still updates.

## Explicitly not
- No change to what the client shows when GitHub is unavailable — #619.
- No change to the pasted-token path (#550).
- No webhooks.
- The renewal implementation (migration + refresh + eviction) — split to a follow-up
  task proposed in the closing report; not yet opened at the time of this record.

## Decisions made along the way
- Cause: GitHub's 2026-08-14 changelog made short-lived tokens (8 h access, 6-month
  refresh) the default for every newly registered OAuth App; the Locklane app was
  registered for #590 (merged 2026-09-02), after that default flipped. The engine keeps
  only `access_token`. Design in `docs/architecture/github-token-lifetime.md`.
  (claude, 2026-09-02)
- The fix needs a Flyway migration (`refresh_token`, `token_expires_at`,
  `refresh_token_expires_at` on `github_accounts`), so per the issue's Goal this task
  stops at the design plus the unprotected instrumentation; the migration is
  deliberately outside this task's Allowed paths. (claude, 2026-09-02)
- Instrumentation shipped here: `GhDeviceFlow.PollResult.Success` now carries
  `token_type`, `scope`, `expires_in`, `refresh_token`, `refresh_token_expires_in`;
  `HttpGhDeviceFlow` parses them; `GhAccountsService` logs a redacted summary at INFO
  and a WARN naming the lifetime when the token expires. Secrets never logged.
  (claude, 2026-09-02)

## Deviations / notes
- The live token response could not be captured in this session: approving a device
  flow is an OAuth grant on the owner's GitHub account, which an unattended agent must
  not perform. The docs/changelog evidence establishes the cause; the exact
  `expires_in` GitHub sends (docs say 28800 s, the issue observed ~3600 s) is what the
  new log line records on the next real sign-in. The human check in the plan covers it.
- The failing install's log is not on the development machine (its two accounts are
  pasted-token and have never failed), so the issue's own figures are the observation
  of record.
- `docs/architecture/` is protected: `/t-review` is required before shipping.
- `./mvnw -B test` fails on the development Mac with nine pre-existing failures in
  `ProjectCheckoutServiceTest`, `ProjectWorktreesServiceTest`,
  `WorktreeCreationServiceTest` and `WorktreeCleanupSweeperTest` (macOS `osxkeychain`
  credential helper and `/private/var` symlink paths); the identical nine fail on an
  untouched `origin/main` checkout. Every test under `engine/.../github/` passes. CI's
  Linux run is the authoritative result. (claude, 2026-09-02)
