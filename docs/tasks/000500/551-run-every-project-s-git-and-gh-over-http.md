# 551 — Run every project's git and gh over HTTPS with its account token in the environment
Issue: #551 · Part of: #549

## Asked
A project's git transport currently follows whatever URL was pasted at import, so a
repository imported over an SSH alias stays on SSH forever, and every fetch and push —
including an agent's `git push` inside an issue worktree — depends on the engine host's
`~/.ssh/config` and keys. Two further gaps: a created project's first push embeds its
token in the `origin` URL (`https://x-access-token:...@github.com/...`), which then
sits in plain text in that checkout's `.git/config`, and issue-worktree agent sessions
receive no `GH_TOKEN` at all (only project consoles do). After this task every git and
`gh` operation for a project goes over HTTPS as the project's GitHub account, with the
token supplied from the environment at run time and never stored in any URL or file.

## Done when
- The URL a project is created with is normalised to `https://github.com/<owner>/<repo>.git`
  before it is stored: accepted inputs are HTTPS forms, `git@github.com:owner/repo(.git)`,
  `git@<any-alias>:owner/repo(.git)`, and bare `owner/repo`. A URL on any other host is
  rejected with a 400.
- The engine's clone and initial push run with `GH_TOKEN` in the environment and a git
  credential helper that answers `username=x-access-token`/`password=$GH_TOKEN`,
  configured repo-locally in the project's main checkout after clone. `git remote -v`
  shows the plain HTTPS URL; the `x-access-token:` URL embedding is removed.
- Every process the engine spawns for a project carries that project's account token as
  `GH_TOKEN`: `CliGhClient` (already did), project console sessions (already did, #550),
  issue-worktree agent sessions (new), and the engine's own git clone/push/fetch
  subprocesses.
- `install.sh` no longer requires `gh auth status` to succeed. The README paragraph
  about acting through the host's `gh` login is replaced by one pointing at the GitHub
  accounts page.
- `./mvnw -B test` passes, with tests for the normaliser, the credential-helper
  configuration after clone, and the worktree session environment.

## Explicitly not
- Rewriting existing projects' SSH remotes or scrubbing embedded tokens from existing
  checkouts: the operator deletes and re-imports.
- Any host other than `github.com`.
- Threading `GH_TOKEN` into `WorktreeCleanupSweeper`'s `git worktree list`/rev-parse
  calls that never touch the network — only its own `git fetch` needed it.

## Decisions made along the way
- The URL normalizer (`GitRemoteUrl`) validates and rewrites the URL synchronously in
  `ProjectController.create`, before any project row exists — the same "fail fast,
  before a row/repository exists" shape #550's account-ownership check and #536's
  template-name check already established. `ProjectCheckoutService.createProject` still
  stores whatever it's handed verbatim; `createNewProject`'s own URL was already
  normalized (it builds `https://github.com/<org>/<name>.git` itself) and needed no
  change (agent, from the plan, 2026-09-02).
- The credential helper (`CREDENTIAL_HELPER_SCRIPT`, a package-visible constant on
  `ProjectCheckoutService`) is configured with plain `git config` after `setUpLocalRepoAndPush`'s
  local checkout already exists, but with `git -c credential.helper=... clone ...` for
  the import path — `clone()`'s destination directory does not exist yet when the clone
  itself needs to authenticate, so there is nothing to configure a repo-local helper
  into beforehand; `-c` applies for the one command regardless (agent, 2026-09-02).
- `setUpLocalRepoAndPush`'s push env is no longer trusted to already carry `GH_TOKEN`
  from its caller: `resolveGithubToken` is called directly inside the method (as it
  already was, just for the URL-embedding branch) and merged into the push's actual
  environment, so a caller passing an empty `pushEnv` (both the 2-arg test overload and
  a resumed/retried flow) still authenticates correctly now that there is no
  URL-embedded fallback (agent, from testing `setUpLocalRepoAndPushWithoutBootstrapInitsCommitsAndPushes`
  and its siblings, which call the 2-arg form directly, 2026-09-02).
- `ProjectConsoleService.environmentFor` is widened in place (a second regex,
  `ISSUE_WORKTREE_SESSION_ID`, tried after the existing console one) rather than
  touching `TerminalWebSocketHandler`'s one call site — the session-id shape alone is
  enough to resolve a project id for every id family (console, worktree, "main",
  "resume"), so no new information needs to flow through that call site (agent, from
  the plan, 2026-09-02).
- `WorktreeCleanupSweeper`'s `git fetch` gained `GH_TOKEN` by adding `GhAccountRepository`/
  `TokenCipher` to its constructor and a private `tokenEnvironment(projectId)` helper —
  the same shape `ProjectConsoleService`/`ProjectGhResources` already use, not a new
  pattern (agent, 2026-09-02).

## Deviations / notes
- The plan's `WorktreeCleanupSweeperTest` validation ("proves the ancestor-check fetch
  carries `GH_TOKEN`") is only partially met: the test fixture's "origin" is a local
  filesystem path (mirroring every other test in that file), and a local-path fetch
  never actually consults git credentials at all — there is no server-side hook
  analogous to a push's `pre-receive` that fires on `fetch`/`upload-pack` to observe the
  child process's environment from. The added test
  (`sweepRemovesAnOrphanedProjectConsoleWorktreeWhenTheProjectHasAChosenAccount`) proves
  the new constructor/token-resolution wiring runs end-to-end without regressing the
  existing guard, not that `GH_TOKEN` specifically reaches the `git fetch` subprocess —
  that narrower claim rests on the same `run(Path, Map, String...)` mechanism
  `ProjectCheckoutServiceTest`'s push-env test already proves directly against a real
  push. Flagged rather than silently claimed as fully covered.
- `install.sh`'s `gh`-binary-presence check is kept (only the `gh auth status` block is
  removed) — the plan left this as an explicit choice rather than the issue's own
  wording deciding it: `gh repo create` and issue/PR fetches for a project with no
  account chosen still run through the host's `gh` binary.
- Existing `ProjectControllerTest` fixtures using `"/does/not/exist"` as a throwaway
  uncloneable `gitUrl` no longer pass the new normalizer (a local path is not one of the
  four accepted shapes) — replaced with a syntactically valid but nonexistent
  `https://github.com/locklane-tests-no-such-org/does-not-exist.git`, confirmed to fail
  fast (~0.3s, no network hang) against the real `github.com` the same way the old path
  failed fast locally.
