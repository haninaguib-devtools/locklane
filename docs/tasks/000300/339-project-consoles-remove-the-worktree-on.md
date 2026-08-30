# 339 — Project consoles: remove the worktree on tab close, with the cleanup sweep as backstop
Issue: #339 · Part of: #337

## Asked
Closing a project console tab ends the PTY session but leaves its worktree on disk
forever — nothing removes it, and the existing cleanup sweep can't help because its
eligibility rule is per-issue (issue closed) and a project console has no issue.
Closing the tab should attempt removal of the worktree, guarded: the session has
ended, HEAD is detached (a checked-out branch means the console outgrew scratch —
leave it), `git status --porcelain` is empty, and HEAD is an ancestor of
`origin/main` (so a commit made on detached HEAD is never lost when the worktree's
reflog goes with it). A worktree failing any check is kept and appears in the
project worktree list with the refusal reason, following the existing
`removalRefusalReason` pattern. `WorktreeCleanupSweeper` gets the same eligibility
rule for project-console worktrees, as the backstop for crashes and
dirty-then-forgotten cases.

## Done when
- Closing a clean, detached, no-commits project console removes its worktree
  directory (verified against real git in tests).
- A dirty worktree, one with a branch checked out, or one with commits not reachable
  from `origin/main` survives tab close and is listed with a human-readable refusal
  reason.
- The periodic sweep removes an orphaned project-console worktree meeting the same
  guard, and only then.
- The new ADR is in `docs/adr/`, referencing ADR-005/ADR-008, and `CONSTITUTION.md`
  §4's operative one-line rule is updated.

## Explicitly not
- Old-style worktrees sitting on `console/*` branches: the branch-checked-out guard
  deliberately skips them; removed by hand if ever in the way (ADR-005).

## Decisions made along the way
- ADR number is **010**, not the issue's suggested numbering — #342 (a sibling child
  of the same initiative) landed first and claimed 009
  (`docs/adr/009-cleanup-sweep-also-deletes-a-swept-worktrees-branch.md`, merged into
  `wip/337-integration` and pulled into this branch before work started).
- The issue's own text cites "ADR-006" for the cleanup-sweep decision this task
  extends; the correct citation is **ADR-008**
  (`008-automatic-worktree-cleanup-sweep.md` — its own revisit trigger 3 is what this
  task fulfills). ADR-006 is `006-single-task-driving.md`, unrelated. The new ADR
  cites ADR-005 and ADR-008 correctly (haninaguib, 2026-08-29, per `/t-plan 339`'s
  research — the same correction sibling task #342 made independently for its own
  citation of the same decision).
- Discovery of project-console worktrees is git-native (`git worktree list
  --porcelain` in the project's checkout), not `WorktreeSessionRecord`-based: unlike
  a per-issue worktree, whose record survives an ordinary disconnect and is deleted
  only by an explicit close, a project console's tab-close already deletes its
  record unconditionally as part of ending the session
  (`SessionRegistry.close`'s existing contract). Keeping the record alive after a
  refused removal, to make the worktree listable later, would incorrectly resurrect
  a closed tab in `ProjectConsoleService.find`/`listOpen`'s open-console view, which
  treats "has a persisted record" as "open" (haninaguib, 2026-08-29, `/t-plan 339`).

## Deviations / notes
- **Re-plan after `/t-review`'s first pass, addressing two High findings.** The
  cold review of PR #361 found: (1) the first implementation pass of
  `allProjectConsoleWorktrees()` discovered candidates with a plain `Files.list`
  directory-name match, never actually calling `git worktree list --porcelain` as
  the Plan's Risks/constraints required and as this record's own "Decisions made
  along the way" claimed was built — a same-named but unrelated directory (a manual
  backup, a stray clone) could have been listed as a discovered project-console
  worktree, though `git worktree remove`'s own refusal of an unregistered path
  happened to prevent any actual removal; (2)
  `engine/src/test/java/dev/locklane/engine/persistence/ProjectConsoleControllerTest.java`
  was modified (a `WorktreeCleanupSweeper` wired into its `ProjectConsoleController`
  test-building helper, mirroring the sanctioned `ProjectConsoleService` constructor
  change) but was never listed in the Plan's Allowed paths, which instead named
  `ProjectWorktreesControllerTest.java` — a file this task never actually touches.
  Corrected by: (1) rewrote `allProjectConsoleWorktrees()` to call
  `git worktree list --porcelain` in each project's checkout and cross-reference its
  `worktree <path>` lines against the sibling-directory naming filter, replacing the
  `Files.list` approach entirely, plus a new real-git test
  (`discoveryIgnoresASameNamedDirectoryThatWasNeverRegisteredAsAWorktree`) proving a
  same-named, never-`git worktree add`-ed directory is never discovered; (2) re-ran
  `/t-plan 339`, replacing the `## Plan` section's Allowed paths test-file line —
  was: `..., ProjectWorktreesServiceTest.java, ProjectWorktreesControllerTest.java,
  GitTestRepos.java`; now: `..., ProjectConsoleControllerTest.java,
  ProjectWorktreesServiceTest.java, GitTestRepos.java` (dropping
  `ProjectWorktreesControllerTest.java`, which this task never touches, and adding
  `ProjectConsoleControllerTest.java`, which it always did) — and added a
  Risks/constraints note making the git-native discovery requirement concrete
  (`git worktree list --porcelain`, not directory-name matching alone). Approved in
  the moment by this task's own driver (`/t-drive` 337, acting per its ADR-004/
  ADR-006 delegated fix-mode authority) — no unrelated content changed. Re-ran the
  targeted Maven tests, the full `./mvnw -B test` suite, and
  `./.t-workflow/scripts/consistency-check.sh`/`check-manifest.sh`/`check-record.sh`,
  all green; see the PR's checks for exact counts.
