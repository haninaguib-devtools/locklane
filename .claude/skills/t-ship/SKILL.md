---
name: t-ship
description: Ship a task — gate, mark the draft PR ready, watch CI, obtain the human's confirmation, squash-merge with a commit written from the record. A child of an initiative merges into the integration branch on the mechanical gate alone; the initiative's own PR is the human's gate. The only path to the trunk. Use when a task or an initiative is finished.
---

# Ship a task

Read `.t-workflow/AGENTS.md`.

1. `.t-workflow/scripts/gate.sh ship <id>`. Any `BLOCKED:` line → stop and relay it
   with the command it names — including a review whose pending human checks or
   findings are unknown because it lacks that section: an absent section is never read
   as none. One exception: a parent (initiative) whose only block is "no PR" names the
   `gh pr create` that opens its integration PR — run it (body: `Closes #<id>`, one line
   per child `- #<child> <title> — docs/tasks/<child>-<slug>.md`, and `## Checks run`
   with the children's), then gate again. Keep the output: it is the evidence for
   step 4. `merge:` says which gate this is: `confirm` (a task, or a parent) asks the
   human in step 4; `automatic` (a child of an initiative) merges on green without a
   question, because the initiative's own PR is where the human decides.
2. `gh pr ready <pr>`. CI starts here (drafts skip it). A PR that was already ready may
   carry a run that went red before its review existed: `.t-workflow/scripts/rerun-ci.sh <pr>`
   re-runs the red runs (a no-op unless a completed run at the head is red). Then
   `gh pr checks <pr> --watch`.
   No CI configured → say so and continue. Red → `gh pr ready <pr> --undo`, report
   which check failed, name `/t-work <id>`. Stop.
3. Do not edit anything. A defect noticed here is a finding for the report, not a fix.
4. `merge: automatic` → skip to step 5 as if answered yes; report what merged and
   into which branch. Otherwise **ask the human to confirm**, last thing in the message, with the PR URL, one plain
   paragraph of what merges and why, and the evidence: review verdict (or "no review
   ran"), CI state, diff size, every pending human check, and every medium and low
   finding still open from the gate's `review-open-findings` lines, each in one plain
   sentence — confirming acknowledges them all; a human who wants one fixed first
   answers no and names it for `/t-work <id>`. Then ask, as the question this stop's
   rule in `.t-workflow/AGENTS.md` calls for: "Merge PR #<pr> into <trunk>?" with
   options `merge` / `no`. Do not merge on silence. On no: `gh pr ready <pr> --undo`
   and stop.
5. On yes:

```bash
gh pr merge <pr> --squash --subject "[<id>] <issue title> (#<pr>)" --body-file <file>
```

Body, from the record:

```
<goal, one line>

Non-goals: <from Explicitly not>
Outcome: <what shipped; notable decisions and deviations>

Task: #<id> — docs/tasks/<id>-<slug>.md
```

For a parent, the body's last lines are one `Task: #<child> — docs/tasks/<child>-<slug>.md`
per child instead. `Closes #<id>` in the PR body closes the issue only when the base
is the default branch; a child's merge into the integration branch does not, so
after it always `gh issue close <id> --reason completed` — the next child's blocker
gate reads "closed as completed".

6. `git fetch --prune`. On the trunk locally, `git merge --ff-only origin/<trunk>`; on
   any other branch, leave the checkout alone. Never delete a worktree or local branch.
7. Report the merge commit, whether a cold review ran, and whether the local trunk was
   fast-forwarded. A child whose parent's children are now all closed: say so and name
   `/t-ship <parent>` (or `/t-review <parent>` first when any child's diff was
   protected) — the parent closes when its own PR merges, never here.
