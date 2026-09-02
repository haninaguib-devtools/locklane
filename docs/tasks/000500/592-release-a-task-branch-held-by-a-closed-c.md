# 592 — Release a task branch held by a closed console worktree when opening its issue console

Issue: #592

## Asked
Opening an issue's console fails when a closed project console's worktree still has
that issue's task branch checked out. This happens in the ordinary flow: a user runs
`/t-work <id>` inside a project console, which checks out `wip/<id>-<slug>` in that
console's own worktree; they close the tab, and the worktree is kept on disk because
its commit is not yet on the project's default branch; they then open the issue
console for `<id>`. `WorktreeCreationService.openIssueWorktree` runs `git worktree
add` for the issue branch and git refuses, because a branch can be checked out in only
one worktree (`fatal: 'wip/<id>-<slug>' is already used by worktree at
'<workarea>/<project>-console-<hash>'`). The engine surfaces a generic "Worktree
creation failed" and the user has no way forward from the UI. Issue-worktree creation
should notice that the branch is held by another engine-managed console worktree and
resolve it itself: when that worktree has no attached session and no uncommitted
changes, detach it and proceed; otherwise fail with a message that names the holding
worktree path and says why it was not released.

## Done when
- The repro above opens the issue console: with a clean, session-less console worktree
  holding `wip/<id>-<slug>`, `POST /api/projects/<p>/issues/<id>/worktrees` succeeds
  and the resulting issue worktree is on that branch.
- A `WorktreeCreationServiceTest` case covers a clean console worktree with no attached
  session holding the branch: creation succeeds and the holder ends up detached (or is
  the reused worktree).
- A `WorktreeCreationServiceTest` case covers a holder with uncommitted changes:
  creation fails, and the exception message contains the holder's path.
- A holder with an attached session is refused the same way, with the path in the
  message (covered by a test if the session lookup is injectable, otherwise stated as
  a human check).
- `./mvnw -B test` passes.

## Explicitly not
- No change to how `/t-work` chooses its checkout, and no change to the cleanup
  sweeper's keep/remove rule for closed console worktrees.
- No client-side change beyond whatever text the existing error surface already shows.

## Decisions made along the way
- The holder is detached rather than reused as the issue worktree (agent, 2026-09-02):
  every other part of the engine — the cleanup sweep, the project page's worktree
  list, `conversationDirectory` — finds an issue's worktree by the
  `<repoName>-<issueNumber>` sibling-directory convention, and a console worktree can
  never be renamed into that shape while git holds it registered under its own path.
- The holder is detached at the project's trunk (`origin/<defaultBranch>`), not merely
  detached in place at the branch tip (agent, 2026-09-02): both release the branch,
  but a console worktree left detached at an un-landed commit would sit outside the
  sweeper's "detached and an ancestor of trunk" removal rule for good — a squash-merge
  never makes that exact commit an ancestor — while one detached at trunk is exactly
  the idle scratch checkout a fresh project console starts as, and is swept normally.
  The branch itself, and every commit on it, lives on in the issue worktree, and the
  holder was verified clean first, so nothing is lost either way. A plain
  `git checkout --detach` is the fallback if detaching at trunk fails for any reason.
- Only a holder named as this engine's own project-console worktree
  (`<repoName>-console-<suffix>`) is ever released (agent, 2026-09-02): the project's
  main checkout or a worktree a human made by hand is refused with the same
  path-naming message, because detaching someone's own checkout to free a branch is
  not the engine's call. A console-named holder whose directory is already gone (a
  stale registration) trivially has no session and no changes, and is released with
  `git worktree prune` — what git itself suggests in that state.
- Session attachment is read from `SessionRegistry#hasLiveSessionIn`, the same check
  the cleanup sweep uses, so the "attached session" refusal is covered by a real test
  (`SessionRegistry` is constructible in tests and `attach` is real). The registry is
  injected through a new primary constructor; the previous six-argument constructor
  stays as a secondary one that builds a registry with no live sessions, mirroring
  `SessionRegistry`'s own test-only constructors, so the four test files outside this
  task's scope that construct the service directly need no edit.

## Deviations / notes
- Worth remembering when running the check set from inside a Locklane console: the
  console's own environment exports `GH_TOKEN` and a `GIT_CONFIG_COUNT`/`KEY_0`/
  `VALUE_0` inline credential helper, which makes three unrelated "project with no
  account gets no token / no helper" tests fail (`ProjectCheckoutServiceTest` ×2,
  `ProjectConsoleWebSocketIntegrationTest` ×1). They pass with those four variables
  unset (`env -u GH_TOKEN -u GIT_CONFIG_COUNT -u GIT_CONFIG_KEY_0 -u GIT_CONFIG_VALUE_0
  ./mvnw -B test`), which is how the recorded PASS was produced; CI has neither.
- A first cut of the holder lookup parsed `git worktree list --porcelain` with a plain
  `split("\n")`, which drops trailing empty strings, so the last listed worktree — the
  holder, in every test — was never examined and every case fell through to git's own
  error. `split("\n", -1)` fixed it; the tests caught it before anything was pushed.
