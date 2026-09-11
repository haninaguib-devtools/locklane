---
name: t-drive
description: Chain the stages for one task — plan if protected, work, review if protected, into the ship gate — or for a parent's children in dependency order, one PR each into the integration branch, stopping at the parent's merge gate. Use to drive or autonomously run a task or initiative.
---

# Drive a task, or a parent's children

The one explicitly-invoked exception to "nothing chains": `/t-drive <id>` runs the
stages below without stopping between them, and stops only where a human must decide.

Read `.t-workflow/AGENTS.md` once for the whole run; the chained stages need not read
it again, but a spawned reviewer always does.

**A task:**
1. `/t-plan <id>` when `gate.sh work <id>` reports a protected scope with no plan.
2. `/t-work <id>`.
3. `/t-review <id>` when the diff is protected, in a subagent. `not-ready` → one fix
   pass (`/t-work <id>`) and one re-review. Still not ready → stop and report.
4. `/t-ship <id>` up to its confirmation gate. That question is the run's stop.

**A parent (`initiative`):** `.t-workflow/scripts/issue.sh children <id>`; for each
open child whose blockers are closed as completed, in that order, run the task
sequence above. Each child is its own PR into the integration branch
`wip/<id>-integration`, and its `/t-ship` merges on the mechanical gate with no
question (`merge: automatic`), so the run continues straight to the next child whose
blockers are now satisfied. A child that fails its bounded retry stops the run with a
report — it is never skipped, and the parent cannot ship while it is open; fixing it
(`/t-drive <child>`) or cancelling it (`/t-cancel <child>`) resumes. When no child is
open: `gate.sh ship <id>` — when it names the `gh pr create` that opens the
integration PR, run it — then `/t-review <id>` in a subagent when the combined diff
is protected, then `/t-ship <id>`; the parent's confirmation question is the run's
stop.

Report at every stop: what was done, what is waiting, and ask per the Communication
rule in `.t-workflow/AGENTS.md`.
