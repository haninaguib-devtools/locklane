---
name: t-cancel
description: Cancel a task that will not be done — record why, force an explicit decision on every dependent, child, and parent issue, then tear down its PR, branch, and worktree. The pipeline's terminal exit. Use to cancel, abandon, drop, or kill a task.
---

# Cancel a task

The only exit that is not a merge. The argument names the issue (`/t-cancel 142`); with
nothing, list open non-initiative issues and ask. Resolve every `tracker:*` / `forge:*`
operation named below via `docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md`
(GitHub by default).

The safety rules come from
[ADR-001](../../../docs/adr/001-phase0-delivery-workflow.md), decision D3. Where this skill and
that ADR differ, the ADR wins and the difference is a defect — flag it, do not
improvise.

Cancelling destroys work. Nothing belonging to a *task* is destroyed before the gate in
Phase 3. The one path that never reaches that gate is a `/t-fix` change (Phase 1 step 2),
which has no issue, no record, and no dependents — closing its PR is the whole of it, and
the human asking for it is the confirmation.

## Where this runs

Teardown may remove the task's worktree, so this skill cannot run from inside it. Compare
the current checkout (`git rev-parse --show-toplevel`) with the primary checkout (the
parent of `git rev-parse --path-format=absolute --git-common-dir`) before any mutation —
labels, comments, issue edits, gates, branch changes, anything:

- **Inside a linked worktree** → stop, report the primary checkout's absolute path, and
  say to run `/t-cancel <id>` from a session rooted there.
- **In the primary checkout** → continue, on `main` or on the task branch. Uncommitted
  changes stop the skill: report them and never stash, discard, or switch over them.

## Phase 1 — is this really a cancellation

1. Read `AGENTS.md`, `CONSTITUTION.md`, and the issue (`tracker:view <id>`).
2. **Refuse, naming the reason and the alternative:**
   - **A merged task.** Undoing shipped work is a revert — ordinary forward work with its
     own diff. Hand off to `/t-open`.
   - **"Not now."** Parking, deferring, waiting on something else — cancellation is
     terminal (ADR-001 D3.5). Deferral is the issue staying open with a `Blocked-by:`
     line. Ask which is meant rather than guessing.
   - **A supersession with no successor named.** A task replaced by a different plan
     closes pointing at the issue that replaced it; find or open that issue first.
   - **A `/t-fix` change** (ADR-001 D3.4): no issue, no record, no dependents. Close
     the PR, delete its branch, and stop — nothing below applies.
3. **Find what exists**, so the gate can name it: `git fetch --prune origin`, then the
   branch locally (`git branch --list 'wip/<id>-*'` with `<id>` lowercased, `PROJ-142` →
   `proj-142` (ADR-001 §D4); `git worktree list`) and on the
   remote, plus any PR (`forge:pr-find-by-task <id>`, all states) and whether a record
   file exists on the branch. A task worked in another session leaves nothing local.
4. **The escape hatch** (ADR-001 D3.1): if the reasoning behind this cancellation is
   durable and constrains future work — "we will not do X, because it conflicts with the
   event model" — or the same idea has now been opened and dropped more than once, it
   belongs in an ADR or `docs/architecture/`, not in a close comment. Say so and open that
   task (`/t-open`); do not smuggle a decision into a cancellation.

## Phase 2 — neighbours, decided before anything is torn down

ADR-001 D3.2 and D3.3. **Gather and decide here; act on nothing yet** — every comment,
label, checkbox, and close happens in Phase 4, so an aborted gate leaves every issue
exactly as it was. Never decide any of them silently, and never cascade.

1. **Dependents.** Every open issue whose body carries `Blocked-by: #<id>` — in either
   shape: inline, or a bare `#<id>` under a `### Blocked by` heading, which is how an
   issue form renders the field. Sweep bodies client-side with `tracker:list-open` rather
   than with the tracker's own body-search, whose index can lag and miss a
   recently-edited issue.

   A result that reaches the backend's page limit is an incomplete scan: report it and stop rather than
   reporting "no dependents". A cancelled blocker was **abandoned, not satisfied** — each
   dependent needs an explicit disposition: proceed anyway, re-point at a different
   blocker, or cancel too (a separate run, decided on its own merits).
2. **Parent.** If the body has `Part of: #<n>` (inline, or under a `### Part of`
   heading), ask the human explicitly whether that
   initiative still adds up without this step. If it does not, that is a second
   cancellation, decided separately — never implied by this one.
3. **Children.** If the issue carries the `initiative` label, list its open children and
   decide **each one individually**: cancel it, or promote it to standalone by dropping
   its `Part of:` line. Children are frequently valuable alone.
4. **Spun-off exclusions.** Issues carrying `Split from: #<id>` — opened from this task's
   Non-goals — hold rationale pointing at a task that will not exist: re-point them at the
   parent or cancel them, never orphan them. Find them in the same `tracker:list-open`
   sweep as the dependents; an issue opened before that marker existed will not be found,
   so say so rather than reporting none.

## Phase 3 — the gate

Before it, in plain prose per AGENTS.md §Communication: what will be destroyed, what will
survive, and every neighbour decision from Phase 2. Then the gate, per
`docs/architecture/confirmation-gates.md`: a plain question (or the environment's native
question mechanism), last thing in the message —

- evidence: destroys `<PR #, branch, worktree — or 'nothing built yet'>` · reason
  `<one line>` · neighbours `<n dependents, parent, children — dispositions>`
- question: "Cancel task #<id> and tear down its work?"
- options: `confirm` (cancel the task) / `abort` (keep it)

`confirm` proceeds; anything else stops, changing nothing. Never proceed on silence.

## Phase 4 — teardown, in this order

1. **Ensure the `cancelled` label exists** before anything is destroyed — discovering it
   is missing afterwards leaves work destroyed with the issue still open. Idempotent:
   `tracker:ensure-labels cancelled` (color `6E7781`, description "Task cancelled via
   /t-cancel — see the close comment for the reason", matching the bootstrap script).
2. **Remove the worktree, if one exists** — a branch checked out in a worktree cannot be
   deleted. Find it with `git worktree list` rather than assuming a path:

   ```bash
   git -C <worktree-path> status --porcelain   # empty output = clean
   git worktree remove <worktree-path>
   ```

   **Refuse to remove a worktree with uncommitted changes** (never `--force`): report what
   is uncommitted, stop the teardown there, and let the human decide.
3. **Close any PR — never repurpose it.** That diff *is* the discarded work, and closing
   it records the abandonment honestly:
   `forge:pr-close <pr>` with a comment saying why, in prose.
4. **Delete the branch, if anything is left to delete.** Step 3's `forge:pr-close`
   already removed the branch — local *and* remote — whenever a PR existed, so this step
   is normally a no-op after a PR, and does the whole job when there was never one.

   `git fetch --prune` first, then return the checkout to a clean, current `main` if it
   is not there already (`git checkout main && git merge --ff-only origin/main`), then
   delete the local branch only if it survives:

   ```bash
   git rev-parse --verify --quiet wip/<id>-<slug> && git branch -D wip/<id>-<slug>
   ```

   **Then the remote branch, and check before deleting.** `git rev-parse --verify
   refs/remotes/origin/<branch>` failing means there is nothing left to do, which is the
   normal path, not an error. Only when that ref still exists, delete it with a lease
   against the exact object ID you just read, so concurrent work fails safely rather than
   being overwritten:

   ```bash
   oid=$(git rev-parse --verify refs/remotes/origin/<branch>) &&
     git push --force-with-lease=refs/heads/<branch>:$oid origin :refs/heads/<branch>
   ```

   A rejected deletion means another session moved the branch: stop without closing the
   issue and report it. That exact-ID lease is the only permitted force option here.
5. **Close the issue, carrying the reason.** A record that already merged to `main` stays
   there; one that only existed on the destroyed branch is gone by design (ADR-001), so
   this comment is the durable account: `tracker:label <id> cancelled`, then
   `tracker:close <id>` as not-planned, the comment carrying why (in prose) and the
   neighbour dispositions — what was decided for each dependent, child, or parent.

   The `cancelled` label is what makes cancellations queryable — `/t-status` counts them
   from it. Keep it.
6. **Execute every Phase 2 disposition** — `tracker:comment` for each note,
   `tracker:edit-body` for the tracking issue's checkbox: the tracking-issue comment and
   checkbox, each dependent's re-point or proceed note, each spun-off issue's new home. None of these
   happened before the gate; all of them happen now. Report what happened to every
   neighbour in plain prose.

## Rules

- **Never destroy before the gate**, and never on silence.
- **Never merge, never push `main`, never force-push** beyond the exact-ID lease above.
- **Never cascade.** Every dependent, child, and parent gets an explicit human decision,
  however tedious.
- **Never cancel to avoid finishing.** A failing check or a hard review finding is a
  re-plan, not a cancellation.
- Cancelling is not deleting: issues, PRs, and their threads stay readable.
