---
name: t-cancel
description: Cancel a task that will not be done — record why on the issue, decide every dependent and child, close its PR, delete its branch. The pipeline's terminal exit. Use to cancel, abandon, or drop a task.
---

# Cancel a task

Read `.t-workflow/AGENTS.md`. Cancellation is a stage: the reason and every
neighbour's disposition land on the issue before anything is destroyed.

An issue already closed as not planned with an open `wip/<id>-revert` PR
(`gh pr list --head wip/<id>-revert`) is a second pass: steps 1 to 4 are done, and
step 4 would close the very PR this pass came to merge. Go straight to step 5's last
three commands — mark that PR ready, watch its checks, squash-merge it — then report.

1. Read the issue (`.t-workflow/scripts/issue.sh view <id>`), what it blocks
   (`issue.sh blocking <id>`), its children (`issue.sh children <id>`), and any issue
   whose body ends `Split from: #<id>` (`gh issue list --search "Split from: #<id>"`).
2. For each neighbour, ask one question with options `proceed` (remove the
   dependency: `gh issue edit <n> --remove-blocked-by <id>`), `cancel too` (run this
   skill on it), or `leave` (it stays blocked by a cancelled issue and shows as such).
   A cancelled blocker is abandoned, never satisfied.
3. Comment the reason and every decision on the issue (`gh issue comment <id>`), then
   `gh issue close <id> --reason "not planned"`.
4. Close the PR if one is open (`gh pr close <pr> --delete-branch`); otherwise delete
   the remote branch (`git push origin --delete wip/<id>-<slug>`). A merged PR is left
   alone: step 5's revert is its undoing. Leave local branches and worktrees alone. A
   parent: its integration PR closes and
   `wip/<id>-integration` is deleted the same way.
5. A child already merged into its parent's integration branch (its PR is merged, its
   record is on `wip/<parent>-integration`) is reverted there, so the initiative stays
   consistent without it: branch `wip/<id>-revert` from `origin/wip/<parent>-integration`,
   `git revert` the child's squash commit — the record goes with it; CI accepts a
   deleted record for an issue closed as not planned, and the parent's gate requires
   it gone — push, and open a PR against the integration branch titled
   `[<id>] Revert: <title>`. It merges on the mechanical gate: mark it ready,
   `gh pr checks --watch`, `gh pr merge --squash`. A protected revert needs a cold
   review first — stop and name `/t-review <id>`; the next `/t-cancel <id>` is the
   second pass above and runs only those three commands. The issue is already closed (step 3), so CI reads
   the revert as a cancelled task's.
6. Report what was cancelled, each neighbour's disposition, and what was deleted.
