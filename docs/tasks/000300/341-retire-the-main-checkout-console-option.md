# 341 — Retire the main-checkout console option
Issue: #341 · Part of: #337

## Asked
An issue console could be opened directly in the project's main checkout
(`WorktreeCreationService.startSession` with `useWorktree=false`, minting
`<project>-<issue>-main-<suffix>` session ids), with several such consoles allowed to
coexist (#29). That let multiple agent sessions share one checkout — the exact thing
the workflow forbids ("two sessions never share a checkout") — in the one place it
could break worst: the primary checkout is what every `git worktree` operation and the
cleanup sweep themselves run from. The option predates project consoles (#314); with
those now a cheap detached scratch worktree, its "console with no worktree yet" use
case is fully covered elsewhere. Remove it: the server paths, the client's
worktree-vs-main picker, and the `-main-` session-id handling, deciding deliberately
what happens to existing persisted `-main-` session records in the list and reopen
(`reopenSession`) paths.

## Done when
- No API path creates a console session whose working directory is the project's main
  checkout.
- The issue page no longer offers the main-checkout choice.
- Reopening a legacy `-main-` conversation is handled deliberately (refused with a
  clear message, or redirected to a worktree) and the chosen behavior is stated here.
- Existing tests pass; removed behavior's tests are removed or repurposed.

## Explicitly not
- Migrating or deleting persisted legacy `-main-` session/resume records beyond making
  the list and reopen paths behave sanely — an old row can still exist in
  `worktree_sessions`/`console_resume_sessions` and is left alone.

## Decisions made along the way
- **Legacy `-main-` conversations are refused on reopen, never redirected to a
  worktree** (haninaguib, 2026-08-29). Claude/Codex key a stored conversation by the
  directory it was captured in; a conversation captured in the project's main checkout
  can only ever be resumed there. Redirecting the reopen to a fresh/existing worktree
  would silently fail to find it (wrong directory) rather than resume it, which is
  worse than a clear refusal. Concretely:
  - `IssueWorktreeService.resumeSessionsForIssue` now excludes any conversation
    captured in a `-main-`-shaped console, so the Overview tab's reopen list never
    shows a dead end in the first place.
  - `WorktreeCreationService.reopenSession` additionally refuses (returns
    `Optional.empty()`, surfaced as the controller's existing 404) any `-main-`-shaped
    `originalWorktreeId` directly, as defense in depth for a caller that reaches the
    id some other way (a stale client, a direct API call) rather than through the
    now-filtered list. This holds even if a legacy main console's session record is
    still live — the refusal never even looks at the recorded working directory.
- **`WorktreeCreationService.startSession` drops the `useWorktree` boolean entirely**
  rather than keeping it and ignoring `false` (haninaguib, 2026-08-29): the two
  remaining overloads (`(projectId, issueNumber)` and `(projectId, issueNumber,
  requestingUsername)`) make "always a worktree" a compile-time guarantee instead of a
  runtime one, so no future caller can silently ask for the retired behavior.
  `WorktreeController.startSession` (`POST .../worktrees`) drops its `worktree` query
  parameter the same way; the client stopped sending it.
- **`ConsoleTabsComponent`'s where-picker is deleted, not just left unreachable**
  (haninaguib, 2026-08-29). #318 had already set `locationChoice=false` on every real
  caller (the issue page and the project-console page), leaving the `main`/`worktree`
  picker, `directWorktree`, `pickerOpen`, `chooseLocation`, and the click-outside/Escape
  handlers that only existed to close that picker as dead code kept "for any future
  caller that wants it back." With the main-checkout location gone for good, there is
  nothing left to pick, so all of it — inputs, methods, template markup, CSS, and their
  specs — was removed; `plusClicked()` now always emits `{ agent: this.defaultAgent }`
  and `OpenConsoleRequest` carries only `agent`.
- The pre-existing exclusion of `-main-`/`-resume-` shaped ids from
  `IssueWorktreeService.allIssueWorktrees()` (the cleanup sweep's input, #319) and from
  `WorktreeCreationService.startSession`'s "reuse an existing worktree session" lookup
  needed no change — both already treat a legacy `-main-` record as "not the issue's
  one reusable worktree session," which is exactly what a record that can no longer be
  created (but may still exist) should keep meaning.
- `console-labels.ts`'s `isMainSession`/tab-labeling logic (labels an already-open
  `-main-`-shaped tab as "main" in the strip) is left untouched: it is display logic
  for whatever session records already exist, not a way to create new ones, and a
  leftover live main console (from before this deploy) should still show up
  correctly if a browser is still attached to it.

## Deviations / notes
- The full `./mvnw -B test` run on this branch fails 7 tests, all in
  `AccountTwoFactorIntegrationTest`, `ForcedPasswordChangeLoginIntegrationTest`, and
  `TwoFactorLoginIntegrationTest` — none touched by this task, none in its scope.
  Confirmed pre-existing and unrelated: the same 7 failures reproduce identically on a
  clean checkout of `wip/337-integration` (this branch's base) with none of this
  task's changes applied, and disappear/reappear based on which integration test
  classes run together (they share a fixed on-disk SQLite path,
  `/tmp/locklane-engine-test/locklane.db`, rather than a per-test `@TempDir` like the
  rest of the suite) — an existing test-isolation defect worth its own issue, not
  fixed here (out of scope; flagged in the closing report rather than opened
  unprompted, per AGENTS.md §Conventions). Every test in this task's actual scope
  (`WorktreeCreationServiceTest`, `WorktreeControllerTest`, `IssueWorktreeServiceTest`,
  the rest of `dev.locklane.engine.persistence`, and the full 495-spec Angular suite)
  passes.
