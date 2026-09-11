---
name: t-review
description: Independently review a task's PR against its issue, plan, record, and the workflow rules, in cold context; post findings and a readiness verdict on the PR. Required before shipping a protected diff. Use to review or check readiness of a task.
allowed-tools: Read, Grep, Glob, Bash, Agent
---

# Review a task

Read-only: findings are posted, nothing is fixed, nothing on the tracker changes.

## Isolation, before reading anything

A reviewer that inherits the implementer's reasoning re-derives it instead of testing
it. Decide the isolation line first, and alongside it the harness and the exact model
id that will actually do the reviewing (never a display name; `unknown` when it cannot
be told) — this is the `model:` line posted below, the only place a review's own
attribution goes; this skill never touches the working tree:
- This session did not implement the task → `isolation: fresh session`, `model:` this
  session's own harness and model.
- It did → spawn a read-only subagent to do the whole review, under `reviewer_model`
  from `.t-workflow/config` when set, else this session's model; a model named on the
  invocation (`/t-review 12 use opus`) wins → `isolation: subagent`, `model:` the
  harness and model the subagent actually ran as.
- No subagent available → on a protected diff, stop and ask for a fresh session;
  otherwise `isolation: same session (<why the change is small enough>)`, `model:`
  this session's own harness and model.

## Procedure

1. Read `.t-workflow/AGENTS.md`, then `.t-workflow/scripts/snapshot.sh review <id>`:
   the issue, its plan, the PR (files, reviews, head sha, its `## Checks run`), the
   full diff, and local state. For an initiative (`children` non-empty, the PR from
   `wip/<id>-integration` to the trunk) the plans and records to check against are
   the children's, from `children[].plan` and `children[].record`; the parent has none
   of its own, and its Human checks are the union of theirs. `local.clean == false`, or `local.head` differing from
   `pr.headRefOid` while on the task branch, is itself a finding: the PR carries only
   what was pushed.
2. Check **scope** (every path within Allowed paths or Scope), **record honesty** (does
   `docs/tasks/<id>-*.md` describe this change truthfully, deviations included),
   **the rules** in `.t-workflow/AGENTS.md` (no weakened check, no tracker write),
   **unexplained removals** (behaviour, content, or tests gone without the issue
   saying so), and for a document deliverable: consistency, ambiguity, completeness —
   not whether it is right, which is the human's call.
3. Checks: `pr.files` through `.t-workflow/scripts/protected.sh` (exit 0 = protected)
   and `docs-only.sh`. A check may be reported as reused only when `## Checks run`
   names that exact command at a sha equal to `pr.headRefOid`, or the project's own CI
   ran that same command at that sha (read its workflow to be sure — t-workflow's own
   job runs the gates, never the build); anything less → run it yourself. A claimed
   documentation-only skip is verified by running `docs-only.sh` on `pr.files`, never
   reused. A claim about what an external service accepts — an API's request shape, a
   permission, a branch rule — is settled by a read-only call when one can settle it,
   never by the tests' stubs.
4. Severity is what a finding does in the pipeline: a task or initiative that can no
   longer reach the trunk, a merge that should not happen, or a gate silently skipped
   is high wherever it lives, a skill sentence included. So, never lower than high: a
   failed check, an unauthorized removal, a path outside scope, a protected path with
   no `## Plan`. Only blocker and high hold the verdict; medium and low are posted for
   the human to decide.
5. Post with `gh pr review <pr> --comment --body-file <file>`:

```markdown
isolation: <line from above>
model: <harness> / <model>
## Checks
- reused — `<command>` PASS at `<sha>` (from /t-work)   |   - ran: `<command>` — PASS|FAIL
## Findings
### Blocker / High / Medium / Low
- <what is wrong, what it would break, where>
## Pending human checks
- <each Human check from the plan, restated plainly — or "none">
readiness: ready | not-ready
```

`not-ready` names the next step, normally `/t-work <id>`. Finding nothing is a normal
outcome: say `ready` plainly. Anything that deserves its own issue is a recommendation
in the body, never an issue you open.

6. `.t-workflow/scripts/rerun-ci.sh <pr>` — the CI runs for this commit were red only
   because no review existed; re-running the red runs is what turns them green. Say
   what it did.
7. Stop. Do not fix, mark ready, or merge. A pass after a fix pass is scoped: verify the
   named findings, inspect what the fixes touched, re-run only the checks they falsify.
