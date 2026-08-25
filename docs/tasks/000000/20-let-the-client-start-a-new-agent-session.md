# 20 — Let the client start a new agent session for an issue
Issue: #20 · Part of: #1

## Asked
From the client, viewing an issue with no live sessions, there should be a way to
start one — resulting in a new worktree tab and a live terminal — without the user
needing to know or type a filesystem path themselves.

## Done when
- From the client, an issue with no live sessions offers a way to start one, and
  doing so results in a new worktree tab showing a live terminal.
- The mechanism for deciding the new session's working directory is documented in
  the task record: how it was decided, not just what was built.

## Explicitly not
- Choosing which agent CLI/harness runs in the new session — out of scope; the new
  session runs whatever `SessionRegistry.attach()` already spawns today (the default
  shell command from #5).

## Decisions made along the way
- Chose direction (a) from the issue's two options: a real `git worktree add` on a
  `wip/<id>-<slug>` branch, in a sibling `../<repo-name>-<id>` checkout — the same
  shape `/t-wtree` already produces for the human pipeline — over a lighter-weight
  non-git convention. Per-worktree isolation is the whole point of this app's agent
  model (ADR-002); a session that silently reused the main checkout or a fake
  directory would undermine that. New `WorktreeCreationService.startSession(issueNumber)`
  (`engine/src/main/java/dev/locklane/engine/persistence/WorktreeCreationService.java`):
  looks up the issue's title via the already-cached `GhIssueCache` (#4) to derive the
  slug, `git fetch --prune` then `git worktree add` off `origin/main` (or off the
  branch directly if it already exists locally or on the remote), idempotent — a
  second call for the same issue returns the existing worktree without recreating it.
  Exposed as `POST /api/issues/{number}/worktrees` on `WorktreeController`
  (engine/src/main/java/dev/locklane/engine/persistence/WorktreeController.java).
- Client: `WorktreeTabsComponent` (#3) gained a "start session" affordance in its
  empty state, wired through `MainContentComponent.startSession()` to the new POST
  endpoint via `IssuesService.startSession()`.

## Deviations / notes
- Live-testing the POST endpoint (against safe, closed issue #2) surfaced a real gap
  the issue's two proposed directions didn't call out: a brand-new worktree has no
  entry in `WorktreeSessionRepository` yet — that table is only written on an actual
  WebSocket attach (#6/#15), which hasn't happened for a session that was just
  created and never connected to. The terminal component's original binding
  (`<app-terminal [worktreeId]="...">`, no `dir`) relies on the backend's
  `TerminalWebSocketHandler.resolveWorkingDirectory()` falling back to
  `SessionRegistry.lastKnownWorkingDirectory(worktreeId)` (#7) when no `?dir=` query
  param is given — which would also be empty for a worktree nothing has ever attached
  to, so the very first connection to a freshly-started session would fail to resolve
  a working directory.

  Fixed by having `WorktreeCreationService.startSession()` return the worktree's
  deterministic path alongside its id (`StartedSession(worktreeId, workingDirectory)`,
  computed the same way whether the worktree already existed or was just created),
  threading it through the POST response, `IssuesService.startSession()`,
  `MainContentComponent` (stored as `selectedWorktreeDir`, cleared on switching to an
  already-known tab since those still rely on the existing fallback), down to
  `<app-terminal [dir]="selectedWorktreeDir">` — matching #7's existing `?dir=`
  first-attach mechanism instead of adding a second one.

## Verification
- `./mvnw -pl engine test` — 52/52 passing, including `WorktreeCreationServiceTest`'s
  real-git-repo integration tests (a throwaway local bare "origin" + working clone,
  no network) covering reuse-without-touching-git, unknown issue, real worktree
  creation on a new branch, and idempotent re-calls.
- `ng test` (client) — 67/67 passing, including the new "start session" affordance,
  loading/error states, and the `dir`-passing behavior in `MainContentComponent`.
- Live end-to-end in a real browser against issue #2 (closed, safe to touch): clicked
  "start session" with no prior worktree tabs, confirmed a real `../locklane-2`
  worktree and `wip/2-...` branch were created, confirmed the new tab
  (`2-build-the-spring-boot-engine-pty-per-wor`) appeared and its terminal connected
  live and landed in the new worktree's directory (`locklane-2` shell prompt) — the
  exact case the working-directory fix above addresses. Test worktree and branch
  removed afterward.
