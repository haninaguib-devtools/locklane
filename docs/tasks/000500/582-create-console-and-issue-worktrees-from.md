# 582 — Create console and issue worktrees from the project's recorded default branch instead of a hardcoded origin/main
Issue: #582

## Asked
Opening a console on a project whose default branch is not `main` failed outright.
Creating a project without t-workflow runs plain `git init`; on a host with no
`init.defaultBranch` configured git names the first branch `master`. The engine
pushed and recorded `master` correctly ("Project 6 ready on branch master"), but every
worktree it then created asked git for the literal `origin/main` and got
`fatal: invalid reference: origin/main`. The same would happen for an imported
repository whose default branch is anything but `main`. The project row already stores
the branch the engine detected (`ProjectRecord.defaultBranch`, filled by `markReady`);
the worktree code now resolves the remote trunk from that field, falling back to `main`
when the record carries none, so a project works the same whatever its trunk is called.

## Done when
- On a project whose recorded default branch is `master`, `POST /api/projects/{id}/console`
  creates the worktree detached at `origin/master`; the engine log no longer contains
  `invalid reference: origin/main` for such a project.
- Opening an issue console on such a project creates the worktree from `origin/master`
  when no `wip/<n>-*` branch exists, and the idle refresh fast-forwards to `origin/master`.
- `grep -n '"origin/main"' …/WorktreeCreationService.java` returns no match; the trunk
  ref is derived from the recorded default branch in one place.
- A record with a null/blank default branch still resolves to `origin/main` (unit test).
- `WorktreeCreationServiceTest` and `ProjectConsoleServiceTest` gain `master`-trunk cases
  (via `GitTestRepos`) covering console creation, issue-worktree creation and the idle
  refresh; `./mvnw -B test` exits 0.
- `./.t-workflow/scripts/consistency-check.sh` exits 0.

## Explicitly not
- The worktree cleanup sweeper and the console-close removal guard still compare against
  a literal `origin/main`; on a `master` project they fail closed (the worktree is never
  removed). Their behaviour is ratified in `CONSTITUTION.md` §4 point 4 / ADR-104 /
  ADR-107 in terms of `origin/main`, so generalising them carries its own constitution
  amendment — split to #583.
- Forcing `git init -b main` on the plain create path: unnecessary once the recorded
  branch is honoured, and no help for imported repositories.
- No change to how `defaultBranch` is detected or stored (`ProjectCheckoutService`,
  `ProjectRepository`).

## Decisions made along the way
- The trunk ref is resolved by one package-visible helper,
  `WorktreeCreationService.trunkRef(ProjectRecord)` → `origin/<defaultBranch>`, with
  `origin/main` for a null/blank branch. The three static git helpers
  (`createWorktree`, `openIssueWorktree`, `createDetachedWorktree`) take the resolved
  ref as an explicit parameter rather than looking the project up themselves, so they
  stay free of repository access and testable against a bare local repo. (agent,
  2026-09-02)
- `createDetachedWorktree` keeps a three-argument overload that resolves to
  `origin/main`, because two test fixtures outside this task's scope
  (`WorktreeCleanupSweeperTest`, `ProjectWorktreesServiceTest`) call it on `main`-trunk
  repos and the scope line does not cover editing them. Both production callers pass
  the project's recorded branch; the overload is documented as fixture-only. (agent,
  2026-09-02)
- `createWorktree(String branch, …)` has no production caller today (its only namesakes
  are private test helpers). It is updated to take the trunk ref like its siblings and
  given a test rather than removed — removal would be a behaviour change the issue did
  not ask for. (agent, 2026-09-02)

## Deviations / notes
- The console-service test for "reopen recreates a gone worktree" on a `master` project
  removes the worktree with `git worktree remove` by hand instead of `close()`: the
  close guard is #583's territory and, on a `master` project, keeps the worktree.
- `./mvnw -B test` run from inside a project console session fails three pre-existing
  tests that are unrelated to this change (`ProjectCheckoutServiceTest`
  `createProjectWithoutAnAccountConfiguresNoCredentialHelper` and
  `createRepoAndPushWithoutAnAccountRunsGhAsTheHostsActiveAccount`,
  `ProjectConsoleWebSocketIntegrationTest.aProjectConsoleSessionWithNoStoredTokenGetsNoGhToken`):
  they assert that no `GH_TOKEN` and no injected `GIT_CONFIG_*` credential helper is
  present, and a console session carries both by design (#572, #573). With those four
  variables unset the full suite passes, 757 tests, 0 failures. Worth its own issue:
  the engine's own tests are not hermetic against the environment its consoles inject.
