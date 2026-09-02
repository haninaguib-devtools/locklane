# 569 — Pass the project's GitHub token to every engine git clone, fetch, and push
Issue: #569

## Asked
Importing a private GitHub repository over HTTPS could fail at the first step: the
engine's `git clone` ran without the project's credentials, GitHub answered
"repository not found", the project was marked FAILED, and every later `gh` call then
logged a misleading "Could not run gh — is it installed and on PATH?" because the
workarea directory never existed. The same gap existed for the other engine-run git
commands that talk to the remote: the `git fetch --prune origin` made when a task
worktree or console worktree is created and during worktree cleanup, and the bootstrap
`git push`. The project already stores its own encrypted GitHub token (#81, #532); the
engine should hand that token to git for every remote operation so a private HTTPS
repo works with no host-level `gh auth setup-git` or SSH workaround. SSH remotes keep
git's own key handling — the injection applies only to HTTPS remotes.

## Done when
- Importing a private repo by HTTPS URL, with a chosen GitHub account whose token can
  read it, ends in `READY` on a host with no git credential helper configured and no
  SSH key. Verified by hand against a private repo.
- Every engine-run remote git command passes the project's token when the remote is
  HTTPS: `git clone` in `ProjectCheckoutService`, the `git fetch --prune origin` calls
  in `WorktreeCreationService` and `WorktreeCleanupSweeper`, and the bootstrap
  `git push` in `ProjectCheckoutService` — each through one shared helper.
- The token reaches git through a mechanism git honours (an inline `credential.helper`
  passed with `git -c`, reading the token from the subprocess environment) — never on
  the command line or embedded in the stored remote URL.
- Importing a repo by SSH URL still works unchanged, and a project with no stored
  token behaves exactly as today (plain git, ambient host credentials).
- A failed clone is logged with git's real error text and the project is marked
  FAILED, as now; the later "is gh on PATH" warning no longer fires for a FAILED
  project (the issue tree returns empty instead of running gh in a missing directory).
- Unit tests cover: HTTPS clone/fetch/push receive the credential injection; SSH URL
  gets none; missing token gets none. `./mvnw -B test` passes.

## Explicitly not
- Git commands run by Claude sessions inside a task worktree keep using the host's git
  credentials; making those act as the project's account is a separate concern.
- No change to how tokens are obtained or stored (#81, #532).
- No change to the SSH path.

## Decisions made along the way
- The shared helper is a small value type, `GitCredential` in
  `engine/.../persistence/`, holding the `-c credential.helper=...` override and the
  `GH_TOKEN` environment entry; `forRemote(url, token)` decides the HTTPS-vs-SSH and
  token-vs-none split in one place and `forProject(id, ...)` resolves a project's
  stored account through the three collaborators every caller already had. Each
  service keeps its own `run(...)` subprocess plumbing and only builds the command line
  and environment through the helper, rather than moving process execution into a new
  shared class (agent, 2026-09-02).
- The `retry` path was the one clone that still ran plain git after #551: it passes no
  account id, and `clone()` only resolved a token when handed one. It now falls back
  to the account already stored on the row, so retrying a failed private import can
  actually succeed (agent, 2026-09-02).
- The inline helper is passed on every remote command even in a checkout whose
  repo-local `credential.helper` #551 already configured — a checkout created before
  #551, or with its config reset, still authenticates, and the sweeper's fetch inside
  a worktree no longer depends on the shared config (agent, 2026-09-02).
- `WorktreeCreationService`'s three static worktree entry points (`createWorktree`,
  `openIssueWorktree`, `createDetachedWorktree`) take a `GitCredential` parameter,
  resolved by the instance caller (`WorktreeCreationService` gained `GhAccountRepository`
  and `TokenCipher` constructor dependencies; `ProjectConsoleService` already had them).
  A failed fetch there is now logged at WARN with git's output, per the engine logging
  convention; it was silently ignored before (agent, 2026-09-02).
- The FAILED-project guard lives in `ProjectGhResources.forProject`: a FAILED project
  gets an uncached context over a no-op `GhClient` (empty issues/PRs), so no `gh` runs
  in the missing directory and a successful retry builds a real context on the next
  lookup. Keyed on the status, not on the directory's existence, so a READY project's
  behaviour is untouched (agent, 2026-09-02).

## Deviations / notes
- The hand-verification done-when (a private HTTPS import ending `READY` on a host
  with no credential helper and no SSH key) cannot be performed from this session — it
  needs a real private repository and a real account token. The unit tests prove the
  command shape and environment each remote call is built with; the end-to-end check
  is left for the human before shipping.
- The `git fetch` credential injection in `WorktreeCreationService` and
  `WorktreeCleanupSweeper` is proven at the helper level (`GitCredentialTest`,
  `forProject`), not by observing the child process: the test fixtures' origins are
  local paths, which never consult git credentials — the same limitation #551's record
  already noted for the sweeper.
- Rebased onto `main` after #554 (PR #567) landed first and conflicted in
  `WorktreeCleanupSweeper`: its new `isBranchLanded` fetch was routed through
  `GitCredential` like the existing ancestor-check fetch, and the retired
  `tokenEnvironment` helper removed. Resolution applied by the agent at the human's
  explicit "resolve it" at the `/t-ship` gate (human, 2026-09-02).
