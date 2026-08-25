---
name: t-wtree
description: Create or reuse a task's git worktree — resolve the task branch idempotently and attach it to a sibling checkout, so the task can be worked in isolation. Optional; use when a task wants its own checkout (parallel work, a long-running task, an engine-launched session).
---

# Prepare a task worktree

Optional (ADR-001). A task can be worked in the current checkout on its own branch; this
skill exists for when it should have a checkout of its own — two tasks in flight at once,
a task that will stay open across sessions, or an engine-launched session that expects
one.

The argument names the issue (`/t-wtree 154`). This skill only prepares a checkout: it
creates no record, edits no task file, and opens nothing on the tracker or the forge.

## Procedure

1. **Identify the checkouts.** The primary checkout is the parent of the absolute git
   common directory (`git rev-parse --path-format=absolute --git-common-dir`); the
   current one is `git rev-parse --show-toplevel`. List what exists with
   `git worktree list --porcelain` rather than guessing from directory names.

2. **Resolve the task branch idempotently.** `git fetch --prune`, then list local and
   `origin/` refs matching `wip/<id>-*`, normalizing away the `origin/` prefix. `<id>` in
   a branch name is the tracker id lowercased (`PROJ-142` → `proj-142`), matching the
   record filename (ADR-001 §D4):

   - exactly one distinct name → reuse it, local or remote;
   - none → derive `wip/<id>-<slug>` from the issue title: lowercase, replace each run
     of non-alphanumeric characters with `-`, trim leading and trailing `-`, truncate the
     slug to 40 characters, then trim a trailing `-` again;
   - more than one → stop and report every candidate. Never choose lexically; two
     branches for one task means two sessions disagreed, and picking one silently loses
     the other's work.

3. **Reuse before creating.** If the branch is already checked out in a registered
   worktree, report that path and stop — a branch cannot be checked out twice, and a
   second directory for the same task is how work gets stranded.

4. **Attach the worktree** at the sibling path `../<repo-name>-<id>`. Refuse if that
   path exists and is not registered for this branch.

   ```bash
   # no branch exists anywhere
   git worktree add -b wip/<id>-<slug> ../<repo-name>-<id> origin/main

   # a local branch exists with no worktree
   git worktree add ../<repo-name>-<id> wip/<id>-<slug>

   # only origin/wip/<id>-<slug> exists
   git worktree add -b wip/<id>-<slug> ../<repo-name>-<id> origin/wip/<id>-<slug>
   ```

   Creating from `origin/main` (already fetched in step 2) needs no state from the
   primary checkout's local `main` — the new worktree's branch is based on the remote tip
   directly, so uncommitted work elsewhere in the primary checkout never blocks this step.

5. **Verify and report.** `git worktree list --porcelain` must map that branch to exactly
   one path. Report the absolute path and the branch, and say what to do next: open a
   session rooted there and run `/t-work <id>`.

## Rules

- Never switch branches in a checkout you did not create, and never create a worktree
  nested inside another.
- Never `git worktree remove --force`. Removal belongs to `/t-ship` or `/t-cancel`, and
  uncommitted work in a worktree is the human's to decide about.
- Preparing a worktree is not starting work: no record, no commit, no PR here.
