# 340 — Issue consoles: stop minting the wip/ branch at console open
Issue: #340 · Part of: #337

## Asked
Opening an issue console used to run `git worktree add -b wip/<id>-<slug>` immediately,
slugging from the issue's title at that moment. That gave every console-opened issue a
branch, even one only ever used to discuss or plan — machine-made litter nobody is
prompted to clean — and split branch-naming authority with `/t-work` (a title change
between console-open and work-start could mint two branches for one issue). Change the
open behavior so opening a console never mints a branch itself: reuse an existing
`wip/<id>-*` branch (local or on origin) if there is one, otherwise leave the worktree
detached at the current `origin/main` and let `/t-work` create the branch once
implementation actually starts. Also, reopening a console on a worktree that is idle
(detached, clean, no commits of its own) should refresh it to the current `origin/main`
instead of handing back a checkout as stale as the day it was created.

## Done when
- Opening a console on an issue with no `wip/<id>-*` branch yields a worktree detached
  at current `origin/main` and creates no branch (real-git test).
- Opening one where `wip/<id>-*` exists locally or on origin checks that branch out.
- Reopening a console on an idle detached worktree fast-forwards it to current
  `origin/main`; a worktree with a branch, dirty state, or its own commits is left
  untouched.
- Existing tests pass.

## Explicitly not
- `/t-work`'s own freshness check when resuming an existing branch — a template-repo
  (t-workflow) change, synced here later via `/t-update`.
- The main-checkout console option — its own task in this initiative, #341.

## Decisions made along the way
- Both places `WorktreeCreationService` recreates the issue worktree from scratch
  (`startSession`'s git-creation branch, and `reopenSession`'s "worktree is gone,
  recreate it" branch) now share one helper, `openIssueWorktree`, so the
  find-existing-branch-or-detach-at-origin/main rule and the idle-refresh rule apply
  uniformly rather than being implemented twice.
- The already-attached-session short-circuit in `startSession`
  (`issueWorktreeService.worktreeIdsForIssue(...)` non-empty) still returns without
  touching git at all, unchanged from before — it answers "is a session already live
  for this issue", a different question from "is the on-disk worktree idle", and the
  existing test asserting it never touches git stays valid.
- "Idle" is defined operationally as: `HEAD` is detached (no `symbolic-ref`),
  `git status --porcelain` is empty, and `HEAD` is an ancestor of `origin/main` (i.e.
  carries no commit that isn't already part of `origin/main`'s history). Any one of
  those failing leaves the worktree untouched. The refresh itself
  (`git checkout --detach origin/main`) is best-effort — a failure there never blocks
  handing back the (already usable) worktree.
- `createWorktree(branch, path, projectRoot)` (explicit-branch, used by
  `ProjectConsoleService` for its own `console/<suffix>` branches, #314) is untouched —
  out of this issue's scope, which is the issue-console path only.

## Deviations / notes
- Rewrote the one pre-existing test that asserted the old mint-a-branch-immediately
  behavior (`createsARealWorktreeOnANewBranch`) into
  `createsARealWorktreeDetachedAtOriginMainWithNoBranchWhenNoneExistsYet`, since the old
  assertion (`currentBranch == "wip/42-add-the-frobnicator"`) directly contradicted this
  issue's done-when. Added `GitTestRepos` helpers (`headCommit`, `localBranches`,
  `createLocalBranch`, `pushNewRemoteBranch`, `commitAndPush`, `checkoutNewBranch`,
  `commitOnDetachedHead`) to support asserting detached-HEAD / idle-refresh behavior
  with real git rather than mocks, consistent with this test file's existing approach.
- `./mvnw -B test` (full suite) was flaky across repeated runs on this machine, failing
  1-6 tests each time in `dev.locklane.engine.security.*` (TOTP/2FA/login integration
  tests) with different tests failing on each run — consistent with time-window-
  sensitive TOTP assertions, not with anything this task touches. Confirmed unrelated:
  the unmodified base branch passed twice cleanly, then a further full run on this
  branch also passed all 445 tests (0 failures) with no code changes in between. Not
  investigated further — out of this task's scope (`WorktreeCreationService` only).
- **Fix pass, addressing `/t-review`'s blocker finding (independent review, PR #357).**
  The review found the PR `CONFLICTING` against its base `wip/337-integration`: a
  sibling task, #338 ("Project consoles: create worktrees detached, without a
  console/ branch"), had already landed on the integration branch and inserted its own
  new method (`createDetachedWorktree`) at the same point in
  `WorktreeCreationService.java` where this task inserts `openIssueWorktree`, with the
  same collision in `GitTestRepos.java`. Resolved by merging
  `origin/wip/337-integration` into this branch and keeping both additions side by
  side — no functional overlap, #338's `createDetachedWorktree` is used only by
  `ProjectConsoleService`, this task's `openIssueWorktree` only by the issue-console
  path. The review also raised a medium finding (once resolved, `openIssueWorktree`'s
  own inline `git worktree add --detach ... origin/main` call could instead delegate
  to `#338`'s `createDetachedWorktree`, so that git invocation has one implementation
  rather than two). Left unaddressed per Fix mode's "medium/low only when the human
  asks by number" — noted here as a recommendation for the human, or a possible
  follow-up issue, rather than acted on unprompted.
  The merge surfaced a second, non-textual conflict the review didn't have the merged
  content to see: `WorktreeCleanupSweeperTest` (from #342, already on the integration
  branch) built its worktree fixtures via `WorktreeCreationService.startSession(...)`
  and then read `git rev-parse --abbrev-ref HEAD` as "the branch to check cleanup
  against" — valid when `startSession` always minted a branch, but after this task's
  change a freshly-opened console's worktree is detached, so that read returned the
  literal string `"HEAD"` and the test silently checked for a branch named `HEAD`
  instead of a real one. One of the two branch-cleanup tests
  (`leavesAnUnmergedBranchAloneAfterRemovingItsWorktree`) failed outright on this; its
  sibling (`removesAWorktreeWhoseIssueIsClosedCleanAndUnattached`) still passed, but by
  accident (a branch named `HEAD` never exists either way). The production code in
  `WorktreeCleanupSweeper` already handled a detached worktree correctly (its own
  `currentBranch` helper filters out the literal `HEAD`, treating it as "no branch to
  delete" — the right outcome for a console that was never worked on). Fixed by having
  the test's `createWorktree` fixture helper check out a real `wip/<id>-<slug>`
  branch right after `startSession` returns, simulating the `/t-work` step these tests
  are actually about, rather than assuming `startSession` still produces one itself.
  Re-ran `WorktreeCreationServiceTest`, `WorktreeCleanupSweeperTest`,
  `ProjectConsoleServiceTest`, and `ProjectConsoleControllerTest` three times each
  (all stable, 0 failures), then the full `./mvnw -B test` suite once more end to end:
  449/449 pass, 0 failures. Re-ran `check-manifest.sh`, `check-record.sh`,
  `protected-paths.sh`/`check-plan-gate.sh` (against `origin/wip/337-integration...HEAD`),
  and `consistency-check.sh` — all pass.
