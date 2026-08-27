---
name: t-ship
description: Ship a task — mark its draft PR ready, obtain the human's confirmation, and squash-merge with a self-contained commit written from the record. The only path to main. Use when a task is finished and ready to merge.
---

# Ship a task

Resolve every `tracker:*` / `forge:*` operation named below via
`docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` (GitHub by default).

The merge stage. The human confirmation here is the strategic pass — does this belong,
does it collide with anything in flight — and, when no cold review ran, it is also the
only read the change gets before `main`.

## Where this runs

Successful shipping deletes the task branch and removes the task worktree if there is
one, so this skill cannot run from inside what it is about to delete. Compare the current
checkout (`git rev-parse --show-toplevel`) with the primary checkout (the parent of
`git rev-parse --path-format=absolute --git-common-dir`) before any mutation — these
commands are read-only, and in particular the PR must not be marked ready before this
check:

- **Inside a linked worktree** → stop. Report the primary checkout's absolute path and
  say to run `/t-ship <id>` from a session rooted there.
- **In the primary checkout** → continue, whether it sits on `main` or on the task branch
  (ADR-001 makes the worktree optional, so the task branch commonly *is* checked out
  here). Uncommitted changes stop the skill: report them and never stash, discard, or
  switch over them.

## Preconditions

Every step below names `<pr>`. **Resolve it from the task id first** with
`forge:pr-find-by-task <id>`, which matches the head branch `wip/<id>-*` — the id
lowercased, `PROJ-142` → `proj-142` (ADR-001 §D4). Keep its `headRefName` too: the
cleanup in steps 4–5 verifies and deletes that exact branch, and it is the only place
the slug is known.
Exactly one open PR → that is `<pr>`. None → the task has not
reached `/t-work`'s draft-PR step; stop and say so. More than one → stop and report every
candidate rather than guessing. A PR that is already merged or closed means this task has
already left the pipeline; say which and stop.

0. **Protected surfaces, from the diff.** Get the PR's own file list
   (`forge:pr-files <pr>`) and pipe it into `bash scripts/protected-paths.sh --stdin`,
   which is `CONSTITUTION.md` §3 in executable form. This list — never a label, never
   what the issue predicted — decides preconditions 1 and 2.

   Read the exit code exactly: **0** = protected (the paths are echoed), **1** = checked,
   none protected, **2** = nothing was checked. Treat 2 as a broken pipeline and stop —
   an empty file list means `forge:pr-files` returned nothing, not that the PR is clean,
   and continuing would skip the plan and review gates on a diff nobody looked at.

1. **Plan.** A protected path in that list with no `## Plan` section on the issue means
   the work grew onto a protected surface after `/t-work` judged it wouldn't. Stop and
   name `/t-plan <id>`; the plan is written against what the diff actually touches, and
   the task then returns through `/t-review`. Nothing here is retroactive paperwork: an
   unplanned protected change is exactly what `CONSTITUTION.md` §3 forbids.

2. **Review.** A cold review is **required when the PR touches a protected surface**.
   Required and missing → stop and name `/t-review <id>`.

   Whether required or not, if a review exists (`forge:pr-reviews <pr>`) its latest
   verdict governs: `not-ready` goes back to `/t-work <id>` in fix mode. A task with no
   review and no protected path continues, and the report says out loud that no cold
   review ran.

   On a protected surface the review's `isolation:` line must not read `same session`;
   that combination is forbidden, and a review missing the line entirely is treated as
   unknown, not as cold — say so and send it back to `/t-review <id>`.

   On a protected surface the review must also cover what is being merged: compare the
   latest review's timestamp with the head commit's (`forge:pr-view <pr>`). Commits after
   it mean that verdict is about a different diff — say so and send it back to
   `/t-review <id>`.
3. CI, if configured, is green: `forge:pr-checks <pr>`. No CI configured is acceptable and
   is said out loud, not silently skipped.
4. The branch merges cleanly against current `origin/main` (`forge:pr-view <pr>`
   reports mergeability). A conflict means another task landed first; resolving it
   produces new code, which goes back through `/t-work`.

   Mergeability is computed asynchronously and commonly comes back **unknown** on a first
   query. Unknown is not "clean": wait a few seconds and ask again, up to about three
   times. If it is still unknown, say so plainly and treat it as unsettled — carry
   `mergeability: unknown` into the gate's evidence rather than asserting a clean merge
   that was never confirmed.
5. **What is about to merge is what was built.** The PR carries only pushed commits, so
   compare the local branch tip with the PR's head (`forge:pr-view <pr>` returns it):

   ```bash
   git rev-parse HEAD          # on the task branch
   ```

   A local commit that never reached the remote merges nothing and would report success
   anyway — the one failure here that looks exactly like a clean ship. Mismatch → stop,
   say which commits are unpushed, and push them (which re-runs CI and, on a protected
   surface, invalidates the review per precondition 2). When this session is not on the
   task branch, say the comparison could not be made rather than assuming it passed.

6. The task record is in the diff and current, deviations included.
7. **Pending human checks**, when a review exists (`forge:pr-reviews <pr>`). The latest review comment's
   `## Pending human checks` section lists judgments no command can settle, so they arrive
   here still open. Three cases, treated differently on purpose:

   - **Checks listed** — carry them into the confirmation. They do not block the merge;
     the human acknowledges them by confirming.
   - **The section reads `none`** — say so in one clause and carry `none` as the evidence
     value.
   - **A review exists but has no such section** — this is **unknown, never `none`**. Stop
     before the gate, say the review does not state them, and ask the human to name
     the checks from the plan or confirm there are none.

   With no review at all, the evidence value is `no review ran`.

## Procedure

1. `forge:pr-ready <pr>` — the draft becomes ready.
2. **Stop and ask the human to confirm the merge**, showing the PR URL
   (`forge:pr-view <pr>`) and a
   one-paragraph what-and-why in plain prose per AGENTS.md §Communication: what the change
   means in ordinary language, not its internal vocabulary. If approval rules are
   configured on the repo, they must also approve on the forge (`forge:pr-approval`).
   Do not merge on silence.

   **Then list the pending human checks from precondition 7**, in prose, immediately
   before the gate — this is the last moment they can be raised, so they belong in the
   same message as the question rather than behind a link to the review. Keep it a
   handoff, not a re-litigation of the review's findings.

   End the message with the gate, per `docs/architecture/confirmation-gates.md`: a plain
   question (or the environment's native question mechanism), last thing in the
   message, presenting this evidence and these options —

   - evidence: review `<verdict, or 'no review ran'>` · CI `<state>` · diff
     `<files/size summary>` · human checks `<the pending checks, or none>`
   - question: "Merge PR #<pr> into main?"
   - options: `confirm` (confirm merge — human checks judged) / `abort` (do not merge)

   **On `abort`, put the PR back into draft** (`forge:pr-draft <pr>`) before reporting.
   Step 1 marked it ready in expectation of a merge that did not happen; leaving it ready
   advertises a change the human just declined, and the next `/t-status` would list it as
   awaiting `/t-ship`. Nothing else is touched.

   **Outstanding checks are acknowledged, not blocking.** Confirming *is* the
   acknowledgement — that is what the option's label says. Nothing can mark a judgment
   settled mechanically, so a gate that refused until they were settled would have no
   exit. What this gate guarantees is visibility at the decision moment. A human who
   wants a check settled first answers `abort`.
3. On confirmation, squash-merge with a **self-contained commit** written from the
   record, via `forge:pr-merge <pr>`. **Before composing that command, re-read
   `docs/adapters/FORGE.md`'s `forge:pr-merge` row and check the exact command string you
   are about to run against it** — the resolve instruction at the top of this file was
   read once, stages ago, and by now the literal command is easy to reconstruct from
   memory instead; a command missing the active backend's branch-deletion flag
   (`--delete-branch` on GitHub) merges silently and leaves the branch stranded on
   `origin` with nothing surfacing the miss (#39). The subject overrides the forge's
   default entirely, so append the PR reference explicitly — it is not added for you:

   ```
   subject: [<id>] <issue title> (#<pr>)
   body:    <goal — one line>

            Non-goals: <from the record's Explicitly not>
            Outcome: <what shipped; notable decisions and deviations from the record>

            Task: #<id> — docs/tasks/<bucket>/<id>-<slug>.md
   ```

   If the tracker auto-closes on merge (`tracker:auto-close-on-merge`), the PR body's
   phrase closes the issue now; otherwise close the issue explicitly here with
   `tracker:close-done` (as completed — e.g. transition a Jira ticket to Done). Never
   `tracker:close`, which closes as not-planned and would read as an abandoned blocker.
4. `git fetch --prune` — deleted `wip/` branches otherwise linger as stale
   `origin/wip/*` tracking refs. Fetch first, then clean up: the same order in
   `/t-fix` and `/t-cancel`.

   **Then verify the remote branch is actually gone.** `forge:pr-merge`'s branch
   deletion can silently skip the remote: gh deletes the local branch first and the
   remote second, and when the local deletion fails it exits without attempting the
   remote one (`docs/adapters/FORGE.md`). The local deletion fails in a completely
   ordinary state — the task branch checked out in the task's own worktree — while the
   merge itself succeeded, so nothing else surfaces the miss (#120, seen shipping #100):

   ```bash
   git ls-remote --heads origin <headRefName>   # empty output = deleted
   ```

   `<headRefName>` is the one kept from PR resolution at the top of Preconditions,
   never a re-derived slug — a guessed name matches nothing and reports the remote
   clean. If the ref survives, delete it explicitly and say so in the report:

   ```bash
   git push origin --delete <headRefName>
   ```
5. **Clean up: two independent actions, both always taken.** Removing the shipped
   task's worktree and putting the invoking checkout right are separate concerns — one
   is about the task's checkout, the other about this session's — and neither replaces
   the other. Treating them as either/or is exactly the defect that left the primary
   checkout's `main` one commit behind `origin/main` after a clean worktree'd ship
   (#121, seen shipping #92): the worktree was removed, and the fast-forward never ran.

   **Action 1 — remove the task's worktree, if it has one** (`git worktree list` — a
   prepared worktree uses `../<repo-name>-<id>`, but an engine-created one may not, so
   act on what git reports):

   ```bash
   git -C <worktree-path> status --porcelain   # empty output = clean
   git worktree remove <worktree-path>
   ```

   Refuse on uncommitted changes and never `--force`: the merge already succeeded, so
   this is not a ship failure — report what is uncommitted and let the human decide.
   No worktree at all is the ordinary no-op. Either way, continue to Action 2.

   **Action 2 — return the invoking checkout to a clean, current state**, whatever
   Action 1 did or skipped. What "current" means depends on the branch this checkout is
   actually on — and the merge may already have moved it: `forge:pr-merge` deletes the
   local branch too and switches off it, so check rather than assume:

   ```bash
   git rev-parse --abbrev-ref HEAD
   ```

   - **On the task branch** (`<headRefName>`): switch and fast-forward —

     ```bash
     git checkout main
     git merge --ff-only origin/main
     ```

   - **On `main`**: fast-forward in place —

     ```bash
     git merge --ff-only origin/main
     ```

   - **On any other branch**: leave the checkout exactly where it is and state the
     reason in the report — it sits on work that is not this task's, and this skill
     has no business moving it.

   Then **delete the local task branch, if it still exists**. The branch name is the
   `headRefName` from the resolution step at the top of Preconditions — the slug is never
   re-derived here, because a guess that does not match deletes nothing and reports
   success:

   ```bash
   git rev-parse --verify --quiet <headRefName> && git branch -D <headRefName>
   ```

   A branch that is already gone is the normal case, not a failure — say so and move on.
   When it does still exist, `-D` and not `-d`, and only here: a squash merge rewrites the
   work into a single new commit, so the branch's commits are never ancestors of `main`
   and `-d` would refuse every time as "not fully merged". The safety `-d` normally gives
   is already supplied — the forge reported the merge succeeded, so the content is on
   `main`. Skip this delete only when Action 1 left the worktree standing (removal
   refused on uncommitted changes): the branch is checked out there and git will
   refuse.
6. If the task belongs to a tracking issue, tick its checkbox
   (`tracker:edit-body` on the tracking issue's task list).
7. Report the merge commit hash, whether a cold review ran, and what happened to the
   branch and any worktree.

   **If that tick completed the tracking issue's list**, say so and ask whether to close
   the initiative — it is the human's call, never automatic, because an initiative can
   outlive its checklist. On a yes, close it as completed (`tracker:close-done`) with a
   comment naming the child tasks that delivered it; on a no, leave it open and say what
   it is still waiting for.

## Rules

- Never merge without the human's explicit confirmation in this conversation.
- Never force-push; never push `main`.
- Never `git worktree remove --force`. Uncommitted work outlives the ship; destroying it
  is the human's call, not this skill's.
- Do not edit the change while shipping. A defect noticed here is a new finding or a new
  issue, not a drive-by fix on the way to `main`.
