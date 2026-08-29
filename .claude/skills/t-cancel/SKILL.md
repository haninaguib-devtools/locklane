---
name: t-cancel
description: Cancel a task that will not be done — record why, force an explicit decision on every dependent, child, and parent issue, then close its PR and delete its branch. The pipeline's terminal exit. Use to cancel, abandon, drop, or kill a task.
---

# Cancel a task

The only exit that is not a merge. The argument names the issue (`/t-cancel 142`); with
nothing, list open non-initiative issues and ask. Resolve every `tracker:*` / `forge:*`
operation below via `docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` (GitHub by
default). The safety rules are
[ADR-001](../../../docs/adr/001-phase0-delivery-workflow.md) decision D3; where this
skill and that ADR differ, the ADR wins — flag it, do not improvise. Nothing belonging
to a *task* is destroyed before the gate in Phase 3.

## Phase 1 — is this really a cancellation

1. Read `AGENTS.md`, `CONSTITUTION.md`, and the issue (`tracker:view <id>`).
2. **Refuse, naming the reason and the alternative:** **a merged task** (undoing shipped
   work is a revert, ordinary forward work — hand off to `/t-open`); **"not now"**
   (cancellation is terminal, ADR-001 D3.5 — deferral is the issue staying open with a
   blocked-by dependency, `tracker:add-blocker`; ask which is meant); **a supersession
   with no successor named** (find or open the replacing issue first, then close
   pointing at it).
3. **Find what exists**, so the gate can name it: `git fetch --prune origin`, then the
   branch locally (`git branch --list 'wip/<id>-*'`; `git worktree list`) and on the
   remote, plus any PR (`forge:pr-find-by-task <id>`, all states) and whether a record
   file exists on the branch. A task worked in another session leaves nothing local.
4. **The escape hatch** (ADR-001 D3.1): durable reasoning that constrains future work,
   or an idea opened and dropped more than once, belongs in an ADR or
   `docs/architecture/`, not a close comment — open that task (`/t-open`) instead of
   smuggling a decision into a cancellation.

## Phase 2 — neighbours, decided before anything is torn down

ADR-001 D3.2–D3.3. **Gather and decide here; act on nothing yet** — every comment,
label, relation change, and close happens in Phase 4, so an aborted gate leaves every
issue as it was. Never decide any silently, never cascade.

1. **Dependents.** `tracker:list-blocking <id>` names every open issue this one blocks
   directly (ADR-003) — a result at the page cap is an incomplete read, report it. A
   cancelled blocker is **abandoned, not satisfied**: each dependent needs a
   disposition — **proceed anyway** (`tracker:remove-blocker`), **re-point** (same,
   then `tracker:add-blocker` to the new one), or **cancel too** (its own run, own
   merits — leave the edge as is).
2. **Parent.** If `tracker:view <id>` returned a `parent`, ask the human explicitly
   whether that initiative still adds up without this task — if not, a second
   cancellation, decided separately, never implied by this one.
3. **Children.** If the issue carries `initiative`, `tracker:list-children <id>` and
   decide **each one individually**: cancel, or promote to standalone
   (`tracker:remove-parent`) — children are frequently valuable alone.
4. **Spun-off exclusions.** Issues carrying `Split from: #<id>` (opened from this
   task's Non-goals) get re-pointed at the parent or cancelled, never orphaned —
   `Split from:` stays body text (ADR-003), so sweep with `tracker:list-open` rather
   than the tracker's own body-search, whose index can lag; a pre-marker issue will not
   be found — say so rather than reporting none.

## Phase 3 — the gate

Before it, in plain prose per AGENTS.md §Communication: what will be destroyed, what
survives, and every neighbour decision from Phase 2. Then the gate, per
`docs/architecture/confirmation-gates.md` —

- evidence: destroys `<PR #, branch — or 'nothing built yet'>` · reason `<one line>` ·
  neighbours `<n dependents, parent, children — dispositions>`
- question: "Cancel task #<id> and tear down its work?"
- options: `confirm` (cancel the task) / `abort` (keep it)

`confirm` proceeds; anything else stops, changing nothing. Never proceed on silence.

## Phase 4 — teardown, in this order

1. **Ensure the `cancelled` label exists** before anything is destroyed: idempotently
   `tracker:ensure-labels cancelled` (color `6E7781`, description "Task cancelled via
   /t-cancel — see the close comment for the reason").
2. **Close any PR — never repurpose it.** Re-read `docs/adapters/FORGE.md`'s
   `forge:pr-close` row first and check the exact command against it — a command
   missing the backend's branch-deletion flag (`--delete-branch` on GitHub) leaves the
   branch stranded on `origin`: `forge:pr-close <pr>` with a comment saying why.
3. **Delete the branch, if anything is left to delete.** Step 2 already removed it
   (local and remote) whenever a PR existed — normally a no-op after a PR, the whole
   job otherwise. This skill does not touch worktrees — a stale one is left alone
   permanently (ADR-005) — so the branch may still be checked out somewhere.

   If the *invoking* checkout is on the target branch, move it off first — the only
   checkout this skill may switch, and only because it stands on the branch about to
   be deleted:

   ```bash
   [ "$(git rev-parse --abbrev-ref HEAD)" = "wip/<id>-<slug>" ] &&
     git checkout main && git merge --ff-only origin/main
   git rev-parse --verify --quiet wip/<id>-<slug> && git branch -D wip/<id>-<slug>
   ```

   A branch still checked out in another worktree refuses to delete — the normal case,
   not an error: report it left in place (`git worktree list`) and move on; never
   switch or remove a worktree that is not the invoking checkout.

   Then the remote branch: a missing `refs/remotes/origin/<branch>` means nothing is
   left to do. Otherwise delete it with a lease against the exact object ID just read:

   ```bash
   oid=$(git rev-parse --verify refs/remotes/origin/<branch>) &&
     git push --force-with-lease=refs/heads/<branch>:$oid origin :refs/heads/<branch>
   ```

   A rejected deletion means another session moved the branch: stop without closing the
   issue and report it — the only permitted force option here.
4. **Close the issue, carrying the reason** — the durable account, since a record that
   only existed on the destroyed branch is gone by design (ADR-001): `tracker:label
   <id> cancelled`, then `tracker:close <id>` as not-planned, the comment carrying why
   and the neighbour dispositions. Keep the `cancelled` label — `/t-status` counts
   cancellations from it.
5. **Execute every Phase 2 disposition** — nothing below happened before the gate, all
   of it happens now: **parent**, if any — `tracker:comment` saying this child is
   cancelled; **each dependent** — `tracker:remove-blocker <dependent-id> <this-id>`
   (re-point continues with `tracker:add-blocker <dependent-id> <new-blocker-id>`), plus
   `tracker:comment` explaining why; **each child promoted to standalone** —
   `tracker:remove-parent <child-id>`, plus `tracker:comment`; **each spun-off issue**
   — `tracker:comment` pointing at its new home.

   Report what happened to every neighbour in plain prose.

## Rules

- **Never destroy before the gate**, and never on silence.
- **Never merge, never push `main`, never force-push** beyond the exact-ID lease above.
- **Never cascade** — every dependent, child, and parent gets an explicit human
  decision, however tedious.
- **Never cancel to avoid finishing** — a failing check or hard review finding is a
  re-plan, not a cancellation.
- Cancelling is not deleting: issues, PRs, and their threads stay readable.
