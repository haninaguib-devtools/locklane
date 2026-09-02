# 550 — Add GitHub accounts signed in through the UI and pick one when adding a project
Issue: #550 · Part of: #549

## Asked
Make GitHub accounts something Locklane owns rather than something it reads off the
host. Today the "GitHub account" picker in both Add Project forms (#532) lists the
accounts `gh` is logged into on the engine host, and copies the chosen account's token
into the project row (`github_token`, #81) via `gh auth token --user`; a project with no
chosen account falls back to whatever `gh` is logged in as. After this task there is a
GitHub accounts page in the UI where the operator signs in to a GitHub account — through
GitHub's device flow (show a code and a link, the operator approves from any browser,
the engine polls and stores the token), or by pasting a token — and the picker lists
exactly those accounts. Projects reference an account; the host's `gh` login state is
never consulted.

## Done when
- A Flyway migration adds `github_accounts` (id, `owner_user_id`, `login`, encrypted
  token via `TokenCipher`, `scopes`, `created_at`) and adds a nullable
  `github_account_id` to `projects`. No code path reads or writes
  `projects.github_token` any more; the column is dropped in the same migration when
  SQLite's `DROP COLUMN` is available, otherwise left unused.
- Device flow: an endpoint starts a login and returns GitHub's user code, verification
  URL, and polling interval; the engine polls GitHub's token endpoint off the request
  thread; a status endpoint reports pending/complete/failed.
- Paste-a-token: an endpoint accepts a token, resolves its login and scopes from GitHub,
  rejects one without `repo` with a clear 400, and stores it.
- List and remove: listing returns each account's login, scopes, and whether `workflow`
  is present; removing an account still referenced by a project is refused with a 409
  naming those projects.
- All account endpoints are owner-scoped.
- Client: a GitHub accounts view reachable from the account menu, alongside Settings and
  Manage users.
- Both Add Project forms' picker reads the accounts list; choosing an account is
  required to create; with no accounts the form says so.
- `ProjectGhResources`, `ProjectConsoleService.environmentFor`, and
  `ProjectCheckoutService` resolve a project's token through its account; a project
  whose account cannot be resolved fails with a clear message rather than falling back
  to the host.
- `PUT /api/projects/{id}/github-token` (#81) is replaced by
  `PUT /api/projects/{id}/github-account`.
- `./mvnw -B test` and the client tests pass.

## Explicitly not
- `install.sh`'s `gh auth status` requirement, `git remote` URL normalisation, the
  credential helper, and threading `GH_TOKEN` into worktree/console sessions — split to
  #551 (already the sibling task; that issue owns them).
- Registering the real GitHub OAuth App and supplying
  `locklane.github.oauth-client-id` — that is a manual step for the human operator
  (recorded below); everything else (the device-flow client, the endpoints, the UI) is
  built and works once a client id is set.
- Migrating existing projects' already-stored `github_token` values forward to an
  account row — ADR-004/#549's own non-goals cover this at the initiative level
  (delete-and-re-add is the answer for the one operator today).

## Decisions made along the way
- `ProjectCheckoutService`'s `ambientGithubToken` Supplier and the `gh auth token
  --user`/`gh auth token` subprocess calls are removed outright, not kept as a
  fallback: #550's own issue text says the ambient fallback is removed, and since a
  chosen account's token is already stored on `github_accounts` (captured once, at
  sign-in), there is no more need to shell out to `gh` to resolve it — `storeTokenForAccount`
  is a plain repository lookup now (agent, 2026-09-01).
- The `workflow`-scope gate (#531) reads the chosen account's `scopes` column — captured
  once when the account was added — instead of re-querying GitHub on every bootstrap
  attempt; this also let `tokenScopes`, `ghTokenScopes`, and `parseOauthScopes` be
  deleted from `ProjectCheckoutService` entirely (their job moved to
  `GhTokenIntrospector`, used only when an account is added) (agent, 2026-09-01).
- `ProjectController` now validates a `githubAccountId` synchronously (ownership check
  against the caller, 400 if foreign/unknown) before a project row is ever created —
  stronger than the old async "stored token lookup fails after the row exists" shape,
  and simpler to test (agent, 2026-09-01).
- Device-flow state lives only in memory (`GhAccountsService`'s own map), never
  persisted: it is a short-lived handshake with GitHub's own `expires_in` (minutes),
  and losing it on a restart is the right behaviour — there is nothing to resume
  (agent, 2026-09-01).
- `GhAccountRepository` lives under `dev.locklane.engine.persistence` (alongside every
  other durable-row repository), while `GhAccount`, the service, and the controller stay
  under `dev.locklane.engine.github` (where the pre-existing #532 classes already were)
  (agent, 2026-09-01).
- The GitHub accounts view is reached from the account menu, a sibling to Settings and
  Manage users, rather than nested inside the Settings dialog: the issue text left this
  as an implementer's call ("reachable from the settings dialog, or its own route"), and
  a sibling entry keeps `AppComponent`'s existing one-boolean-flag-per-dialog shape
  instead of introducing dialog-stacking (agent, 2026-09-01).
- `ProjectController` no longer takes a `TokenCipher` — its only use was the removed
  `setGithubToken` endpoint; `setGithubAccount` needs no encryption at the controller
  layer (the account's token is already stored encrypted) (agent, 2026-09-01).

## Deviations / notes
- `ProjectRepository.findGithubAccountId` reads via `ResultSet#getLong` +
  `wasNull()`, not `getObject()` cast to `Long`: the SQLite JDBC driver can hand back a
  plain `Integer` for an `INTEGER` column depending on the stored value's magnitude, and
  the direct cast threw `ClassCastException` in twelve tests before this fix — caught by
  the full `./mvnw -B test` run, not by compilation (agent, 2026-09-01).
- `GhAccountRepository.insert` reads the freshly-inserted row back by
  `(owner_user_id, token)` rather than `last_insert_rowid()`: `JdbcTemplate` does not
  guarantee the follow-up query reuses the same physical connection the insert ran on,
  and `last_insert_rowid()` is connection-local — the same hazard `ProjectRepository
  #create` already avoids via `findByWorkareaPath` (agent, 2026-09-01; not a defect
  found in review, just applying the existing pattern).
- No UI was added for changing an *existing* project's chosen account
  (`PUT .../github-account` exists and is tested, but nothing in the client calls it
  yet) — the old `github-token` endpoint it replaces was never exposed in the UI either
  (grepped for confirmation), and #550's own done-when only requires the picker at
  creation time (agent, 2026-09-01).
- `HttpGhDeviceFlow` takes its two GitHub endpoint URIs as constructor parameters
  (test-only overload) rather than hardcoded constants, so `HttpGhDeviceFlowTest` can
  point it at a local `com.sun.net.httpserver.HttpServer` stub instead of ever reaching
  `github.com` — no new test dependency, the JDK already ships that class (agent,
  2026-09-01).
- Manual step for the human operator, not done by this task: register an OAuth App
  named "Locklane" at <https://github.com/settings/developers>, enable "Enable Device
  Flow" under its settings, and set `locklane.github.oauth-client-id` (env var
  `LOCKLANE_GITHUB_OAUTH_CLIENT_ID`, or the property in
  `~/.locklane/application-locklane.properties`) to its Client ID. Until that is done,
  `POST /api/github/accounts/device/start` answers 501 and the accounts page shows
  "device sign-in isn't set up on this host" — pasting a token still works.
