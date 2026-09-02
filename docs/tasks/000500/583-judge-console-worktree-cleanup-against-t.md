# 583 — Judge console worktree cleanup against the project's default branch, not a hardcoded origin/main
Issue: #583

## Asked
The automatic cleanup of console worktrees decides whether a worktree's work has
"landed" by comparing against the literal `origin/main`. On a project whose default
branch is something else (for example `master`, which plain `git init` produces on a
host with no `init.defaultBranch`), every such comparison errors out and is treated
as "not landed", so the worktree is never removed — safe, but every console ever
opened on that project leaks a checkout on disk. Make the sweeper and the
console-close removal guard judge against the project's recorded default branch
(`ProjectRecord.defaultBranch`, falling back to `main` when absent), the same trunk
the worktree was created from.

This changes ratified behaviour: `CONSTITUTION.md` §4 point 4 and ADR-104/ADR-107
state the guard in terms of `origin/main`, so the task amends that wording (an ADR
amending ADR-104/107, and the constitution paragraph) to say "the project's default
branch on origin" alongside the code change.

## Done when
- On a project whose recorded default branch is `master`, an ended, clean console
  worktree detached at an ancestor of `origin/master` is removed by the sweep and by
  tab close; one with its own unlanded commits is still left alone. Covered by
  `WorktreeCleanupSweeperTest` using a `master`-trunk test repo.
- `grep -n 'origin/main' engine/src/main/java/dev/locklane/engine/persistence/WorktreeCleanupSweeper.java`
  matches only Javadoc/comments that describe the default, or nothing; the ref is
  built from the recorded branch.
- A new ADR amends ADR-104/ADR-107 and `CONSTITUTION.md` §4 point 4 reads "the
  project's default branch on origin" (or equivalent) instead of `origin/main`;
  `./.t-workflow/scripts/consistency-check.sh` exits 0.
- `./mvnw -B test` exits 0.

## Explicitly not
- Worktree *creation* from the recorded branch — that was #582.
- Any change to how the default branch is detected or stored.

## Decisions made along the way
- none

## Deviations / notes
- none
