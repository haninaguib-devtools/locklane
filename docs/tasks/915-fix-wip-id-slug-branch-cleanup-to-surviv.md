# 915 — Fix wip/<id>-<slug> branch cleanup to survive squash merges
Issue: #915

## Asked

After a task's worktree is removed under ADR-102/103, its `wip/<id>-<slug>` branch
must reliably get deleted once the branch's work has actually landed on the target
branch — not just when it happens to still be a fast-forward ancestor.

Today `WorktreeCleanupSweeper` (`engine/src/main/java/dev/locklane/engine/persistence/WorktreeCleanupSweeper.java`,
around lines 261-265) deletes the branch with a plain `git branch -d`:

```java
branch.ifPresent(name -> run(project.get().workareaPath(), "git", "branch", "-d", name));
```

`/t-ship` always squash-merges (`.t-workflow/AGENTS.md` — "Human-confirmed squash
merge. The only path to the trunk."), which replaces the branch's commits with one new
commit on the target branch. `git branch -d` decides "merged" purely by ancestry
(is the branch tip reachable from the target?), and a squash merge breaks that chain by
design — so `-d` always refuses with "not fully merged" for a squash-merged branch, even
though the work landed. ADR-103's "an unmerged one is refused and left alone" fallback
was meant for genuinely unmerged/abandoned branches; `git branch -d` can't tell that
case apart from "squash-merged and actually done," so every closed task silently leaves
its branch behind forever.

Confirmed in production: 12 stale `wip/*` branches in the `locklane` repo and 12 in
`thyme-clinic`, every one confirmed `MERGED` via `gh pr list --state all --head <branch>
--json state,mergedAt`, none ever deleted, growing by one on every task closed since
project inception. Git's own worktree registry (`.git/worktrees/`, `git worktree list`)
was already clean in both repos — the worktree removal itself works correctly — only
the branch delete step fails silently. These orphaned branches also surface as phantom
"previous worktrees" in IDE worktree/branch pickers (e.g. IntelliJ IDEA), which is how
the problem was first noticed.

## Done when

- After a worktree is removed under ADR-102/103, the `wip/<id>-<slug>` branch is
  deleted when the branch's work has landed on the target branch via any merge style
  used by this project (fast-forward, regular merge, or squash merge) — verified by
  something stronger than plain `git branch -d` ancestry (e.g. confirming the merged
  PR's squash commit, or a content/patch-equivalence check), not merely "ancestry check
  failed therefore leave alone."
- A branch whose work has *not* landed (genuinely unmerged, abandoned, or the PR was
  never merged) is still left alone, exactly as ADR-103 requires — this fix must never
  force-delete on an ancestry-check failure alone; a positive "this landed" confirmation
  is required before deleting.
- A one-off cleanup path (script or task) removes the existing backlog of already-stale
  `wip/*` branches in `locklane` and `thyme-clinic` left over from before this fix, and
  the same is checked for any other project under `.locklane/workareas/`.

## Explicitly not

Changing the merge strategy `/t-ship` uses (squash merge stays). Not touching
worktree *directory* removal — that already works correctly and is out of scope.

## Decisions made along the way
- none

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
