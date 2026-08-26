# 81 — Store an encrypted per-project GitHub token and scope issue/PR fetches through it
Issue: #81

## Asked
Today every project's issue/PR data comes from one global `gh` CLI call, scoped to
whatever repo the engine itself happens to be checked out in — so every added
project (#42) shows the exact same shared repo's issues, regardless of which repo it
was actually added for. This gap was first identified in #48 and confirmed again in
#43, which nested every route under a project id but deliberately left the
underlying data source untouched. Let the user supply a GitHub token per project,
store it encrypted at rest (reusing the engine's existing `EncryptionKeyProvider`,
#47), and scope each project's issue/PR fetches to use that project's own token and
repo instead of the one shared global call.

## Done when
- A project can have a GitHub token associated with it, stored encrypted (never
  plaintext) in SQLite.
- Fetching issues/PRs for a project (`IssueController` and the services behind it)
  authenticates as that project's own token, against that project's own repo — not
  the one global `gh` call every project currently shares.
- A project with no token stored still clones and creates worktrees fine (unaffected,
  #42/#43); its issue/PR endpoints return a clear, documented result (e.g. an empty
  list, or a specific error) rather than silently falling back to another project's
  data.
- The bootstrap/self-hosted project (auto-registered by `ProjectBootstrapper`, #43)
  keeps working — either it gets a token too, or its existing no-token behavior is
  explicitly preserved.

## Explicitly not
- UI for entering/managing the token — a separate task if one turns out to be needed
  beyond a raw API call.
- An OAuth/GitHub App flow — a pasted personal access token is enough for this pass.

## Decisions made along the way
- **`TokenCipher`** (new, `dev.locklane.engine.security`) is the first real use of
  `EncryptionKeyProvider` (#47) — it only ever generated/loaded the key file before
  this. AES-GCM, a random 12-byte IV per call prepended to ciphertext+tag and the
  whole thing Base64-encoded as one opaque string, so a caller has a single value to
  persist (haninaguib, 2026-08-26).
- **`CliGhClient`** changes from a no-arg singleton to `(Path workingDirectory,
  String token)`: every call runs with that directory as its `ProcessBuilder` cwd,
  so `gh` resolves the repo the exact same way it already did for the engine's own
  checkout (auto-detection from the directory's own git remote) — never an explicit
  `--repo`, which would need parsing an owner/repo pair out of an arbitrary git URL
  string (SSH vs HTTPS forms, custom hosts, etc.). `token`, when present, becomes
  `GH_TOKEN` in the subprocess environment; `null` falls back to whatever `gh auth
  login` session the host already has — exactly today's single-project behavior for
  a project with no token stored (haninaguib, 2026-08-26).
- **`GhIssueCache`/`IssueDetailService`/`IssueTreeService` stop being Spring
  singleton beans** (`@Service` removed from all three) — they are built one set
  per project by a new registry instead of once, shared by everyone. Their own
  constructors/methods are otherwise unchanged, which kept every existing test in
  `IssueDetailServiceTest`/`IssueTreeServiceTest`/most of `GhIssueCacheTest`
  passing with zero changes.
- **New `ProjectGhResources`** (`@Service`, one real `@Autowired` constructor plus a
  test-only one taking a pluggable client factory) builds and caches one
  `ProjectGhContext` (client + cache + detail/tree services) per project, keyed by
  project id in a `ConcurrentHashMap`; `evict(id)` forces a rebuild (called after a
  project is deleted, and after its token changes, so the very next fetch picks up
  new credentials without a restart); a single `@Scheduled` `refreshAll()` replaces
  the refresh `GhIssueCache` used to schedule for itself, since per-project
  instances built via `new` are no longer Spring-managed and can't self-schedule
  (haninaguib, 2026-08-26).
- **`GhIssueCache`'s cold-fetch path now catches a failed live `gh` call and
  returns an empty list** instead of throwing — previously an unhandled exception
  would have surfaced as a 500. This is what actually delivers the done-when's "a
  clear, documented result… rather than silently falling back to another project's
  data": with one cache per project there is no other project's data left to fall
  back to, so what remains to get right is turning an auth/access failure into a
  clean empty result (haninaguib, 2026-08-26).
- **`IssueController` needed real code changes this time** — #43 could leave it
  alone since issue data stayed global, but resolving through
  `ProjectGhResources.forProject(projectId)` now makes an unknown project a genuine
  404 on every issue-read endpoint too, not just worktree ones.
- **`IssueDetailService`'s task-record lookup (`docs/tasks/`) is now scoped to each
  project's own workarea** instead of the engine's own `locklane.project-root` —
  once issue data is genuinely per-project, a record lookup against the wrong
  repo's `docs/tasks/` would show the wrong (or a coincidentally-numbered
  unrelated) record. Safe for a project that doesn't use this pipeline template at
  all: the existing `Files.isDirectory(tasks)` guard already returns empty rather
  than erroring.
- **New `PUT /api/projects/{id}/github-token`** (raw API, explicitly in scope per
  the issue's Non-goals) stores the token via `TokenCipher.encrypt`; blank → 400,
  unknown project → 404. Added to `SecurityConfig`'s authenticated matchers
  alongside the other project-CRUD endpoints.
- **Found and fixed a real bug while wiring this up**:
  `ProjectRepository.findGithubToken`'s original `.stream().findFirst()` NPE'd on a
  project whose `github_token` column is SQL `NULL` (the common case — every
  project with no token stored) — `Stream.findFirst()` wraps its result in
  `Optional.of(...)` internally, which throws on a null element. Rewritten as a
  plain list-index check.
- `github_token` is added straight into the existing `CREATE TABLE IF NOT EXISTS
  projects (...)`, matching #48/#52's established precedent for this codebase (no
  migration tooling exists yet) — a database created before this task won't gain
  the column and every issue-read call against it will fail until the local
  `locklane.db` is dropped and recreated. Confirmed exactly this failure mode
  against my own pre-existing scratch verification database below, and worked
  around it there the same way #48/#52 already accepted: drop and recreate.

## Deviations / notes
- Manually verified against real GitHub data, not just the test suite and not just
  a synthetic double: ran the engine on an isolated port/data-dir (so as not to
  touch the human's own already-running dev instance on 8080/4200). Hit the known
  "existing SQLite db doesn't gain a new column" gap on my own pre-existing scratch
  database from #44/#45's manual testing (an `UncategorizedSQLException: no such
  column: github_token`) — exactly the precedented, accepted limitation, not a
  regression; dropped that scratch db and started clean. From there: project 1
  (the bootstrapped Locklane checkout, no token) correctly listed 47 real issues
  including #81, #77, #75 via the ambient `gh` auth session, unchanged from before
  this task. Added a second real project against `github.com/octocat/Hello-World`
  and confirmed its `/api/projects/2/issues` returned entirely different, real
  issues from that repo (`#11012 "This should fail"`, etc.) — proof the two
  projects are no longer sharing one cache the way they did before this task.
  Exercised the token endpoint end to end: setting a token returns 204 and evicts
  the cached client immediately (no restart needed); a blank token is a 400 with
  `{"error":"token is required"}`; an unknown project id is 404; an unauthenticated
  request is 401. Set a deliberately-invalid token on the Hello-World project and
  confirmed its issues endpoint degraded to `200 []` rather than a 500 — the
  done-when's "clear, documented result" in practice (haninaguib, 2026-08-26).
