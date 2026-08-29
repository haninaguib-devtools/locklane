---
name: t-ship
description: Ship a task — mark its draft PR ready, obtain the human's confirmation, and squash-merge with a self-contained commit written from the record. The only path to main. Use when a task is finished and ready to merge.
---

# Ship a task

Resolve every `tracker:*` / `forge:*` operation named below via
`docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` (GitHub by default). The merge
stage: the human's confirmation is the strategic read, and, when no cold review ran,
the only read the change gets before `main`.

## Preconditions

Every step below names `<pr>`. **Resolve it from the task id first** with
`forge:pr-find-by-task <id>`, matching head branch `wip/<id>-*`. Exactly one open PR →
that is `<pr>`. None → the task hasn't reached `/t-work`'s draft-PR step, stop and say
so. More than one → stop, report every candidate. Already merged or closed → the task
has already left the pipeline, say which and stop.

0. **Protected surfaces, from the diff.** Get the PR's own file list
   (`forge:pr-files <pr>`) and pipe it into `bash .t-workflow/scripts/protected-paths.sh --stdin`
   (`CONSTITUTION.md` §3 in executable form) — never a label, never what the issue
   predicted — to decide preconditions 1 and 2. Read the exit code exactly: **0** =
   protected (paths echoed), **1** = checked, none protected, **2** = nothing was
   checked — a broken pipeline, not a clean PR; stop rather than skip the plan and
   review gates on a diff nobody looked at.

1. **Plan.** A protected path with no `## Plan` section on the issue means the work grew
   onto a protected surface after `/t-work` judged it wouldn't (`CONSTITUTION.md` §3).
   Stop and name `/t-plan <id>`; the task then returns through `/t-review`.

2. **Review.** Required when the PR touches a protected surface; missing → stop and name
   `/t-review <id>`. If a review exists (`forge:pr-reviews <pr>`), its latest verdict
   governs regardless: `not-ready` → `/t-work <id>` in fix mode. No review and no
   protected path → continue, saying out loud that none ran.

   On a protected surface, two more checks: the `isolation:` line must not read `same
   session` (a missing line is unknown, treated the same way); the latest review's
   timestamp must be newer than the head commit (`forge:pr-view <pr>`). Either failure →
   stop, send it back to `/t-review <id>`.
3. CI, if configured, is green: `forge:pr-checks <pr>`. No CI configured is acceptable,
   said out loud.
4. The branch merges cleanly against current `origin/main` (`forge:pr-view <pr>`). A
   conflict means another task landed first; resolving it goes back through `/t-work`.
   Mergeability is computed asynchronously and often returns **unknown** at first: wait
   a few seconds and retry, up to about three times; still unknown → carry
   `mergeability: unknown` into the gate's evidence rather than asserting a clean merge
   that was never confirmed.
5. **What is about to merge is what was built.** The PR carries only pushed commits;
   compare the local branch tip (`git rev-parse HEAD`, on the task branch) with the
   PR's head (`forge:pr-view <pr>`). Mismatch → stop, say which commits are unpushed,
   push them (re-runs CI and, on a protected surface, invalidates the review per
   precondition 2). Off the task branch → say the comparison could not be made.
6. The task record is in the diff and current, deviations included — or, for a driven
   initiative's aggregate PR (`/t-drive`, ADR-004, branch `wip/<id>-integration`), every
   included child's own record is in the diff, each already current from its own merge
   into the integration branch.
7. **Pending human checks**, when a review exists (`forge:pr-reviews <pr>`): its
   `## Pending human checks` section lists judgments no command can settle. **Checks
   listed** → carry them into the confirmation, non-blocking, acknowledged by
   confirming. **Reads `none`** → say so, carry `none` as the evidence value. **A
   review with no such section** → **unknown, never `none`**: stop before the gate and
   ask the human to name the checks from the plan or confirm there are none. No review
   at all → the evidence value is `no review ran`.

## Procedure

1. `forge:pr-ready <pr>` — the draft becomes ready.
2. **Stop and ask the human to confirm the merge**, showing the PR URL
   (`forge:pr-view <pr>`) and a one-paragraph what-and-why in plain prose per AGENTS.md
   §Communication, then **the pending human checks from precondition 7** — the last
   moment they can be raised. If approval rules are configured on the repo, they must
   also approve on the forge (`forge:pr-approval`). Do not merge on silence.

   End the message with the gate, per `docs/architecture/confirmation-gates.md`: a plain
   question (or the environment's native question mechanism), last thing in the
   message —

   - evidence: review `<verdict, or 'no review ran'>` · CI `<state>` · diff `<files/size
     summary>` · human checks `<the pending checks, or none>`
   - question: "Merge PR #<pr> into main?"
   - options: `confirm` (human checks judged) / `abort` (do not merge)

   **On `abort`, put the PR back into draft** (`forge:pr-draft <pr>`) before reporting —
   step 1 marked it ready in expectation of a merge that did not happen; nothing else is
   touched. **Outstanding checks are acknowledged, not blocking** — confirming *is* the
   acknowledgement; a human who wants a check settled first answers `abort`.
3. On confirmation, squash-merge with a **self-contained commit** written from the
   record, via `forge:pr-merge <pr>` — **re-read `docs/adapters/FORGE.md`'s
   `forge:pr-merge` row first and check the exact command against it.** The subject
   overrides the forge's default entirely, so append the PR reference explicitly.

   **Ordinary task (the common case, unchanged):**

   ```
   subject: [<id>] <issue title> (#<pr>)
   body:    <goal — one line>

            Non-goals: <from the record's Explicitly not>
            Outcome: <what shipped; notable decisions and deviations from the record>

            Task: #<id> — docs/tasks/<bucket>/<id>-<slug>.md
   ```

   **A driven initiative's aggregate PR** (`/t-drive`, ADR-004 Decision 3 — branch
   `wip/<id>-integration`, `<id>` the initiative's own id): the body's goal/non-goals
   come from the initiative issue itself, which carries no record of its own; one
   `Task: #<id>` line per **included** child, never a blended paragraph, each naming
   that child's own record — read the included/excluded lists straight from the PR's own
   body, which `/t-drive` Phase 3 step 1 already wrote them into, rather than
   re-deriving them:

   ```
   subject: [<id>] <initiative title> (#<pr>)
   body:    <goal — one line, from the initiative issue>

            Non-goals: <from the initiative issue's own Non-goals>
            Outcome: <children included, by number; children excluded, by number and
                     why — from the PR body's own lists>

            Task: #<child-1-id> — docs/tasks/<bucket>/<child-1-id>-<slug>.md
            Task: #<child-2-id> — docs/tasks/<bucket>/<child-2-id>-<slug>.md
            …
   ```

   If the tracker auto-closes on merge (`tracker:auto-close-on-merge`), the PR body's
   phrase closes the issue now — one such phrase per `Task:` line, so a driven PR closes
   every included child, never only the initiative; otherwise close each explicitly with
   `tracker:close-done` (as completed) — never `tracker:close`, which closes as
   not-planned. A child's own PR into the integration branch (`/t-drive` Phase 2 step 6)
   never carries this phrase — merging into a non-default branch does not trigger the
   forge's auto-close, and a child is not done until its work reaches `main` through
   this PR.
4. `git fetch --prune` (deleted `wip/` branches otherwise linger as stale
   `origin/wip/*` refs), then **at most, fast-forward a `main` this checkout happens to
   be sitting on** (ADR-002) — merging leaves the task's worktree, local branch, and
   this checkout untouched, left alone permanently (ADR-005), never a side effect of
   shipping: `git rev-parse --abbrev-ref HEAD`, then **on `main`**: `git merge --ff-only
   origin/main`; **on any other branch**, including the task branch: leave it exactly
   where it is — the normal outcome now that shipping runs from anywhere.
5. A `tracker:view <id>` `parent` field names a tracking issue whose `subIssuesSummary`
   the task's close above already updated — nothing to write here.
6. Report the merge commit hash, whether a cold review ran, and whether this checkout's
   `main` was fast-forwarded. **If `subIssuesSummary` (`tracker:view <parent-id>`) now
   shows every child closed**, ask whether to close the initiative too — never
   automatic. For a driven run that just shipped, `<parent-id>` is `<id>` itself (the
   initiative just driven): every included child closed above, and an excluded child, if
   any, stays open — so `subIssuesSummary` reads all-closed only when nothing was
   excluded; the same yes/no question applies either way. Yes → close as completed
   (`tracker:close-done`), comment naming the
   delivering tasks. No → leave it open and say what remains.

## Rules

- Never merge without the human's explicit confirmation in this conversation.
- Never force-push; never push `main`.
- Do not edit the change while shipping — a defect noticed here is a new finding or
  issue, not a drive-by fix.
