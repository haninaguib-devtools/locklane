# t-workflow

How a change moves from an idea to the trunk in this repository. Binding on every
agent session. Scripts under `.t-workflow/scripts/` make the judgments; skills call them.

## Rules

1. **Work starts from a tracker issue.** Changing any file — code, config, docs — is
   work; answering questions and reading is not. Never edit the tree outside a task's
   own `/t-work` session, however small the ask. The one exception is a repository's
   genesis commit.
2. **Every task carries a record**, `docs/tasks/<id>-<slug>.md`, in its PR.
3. **The trunk moves only by a pull request a human confirmed.** That confirmation is a
   rule the skills follow, not an approval GitHub enforces. Never commit or push
   to it directly.
4. **A protected diff needs a `## Plan` on its issue before implementation and a cold
   review before shipping.** Protection comes from the paths a diff touches, never from
   a label.
5. **A skill runs only when the human named it**, and nothing chains from one stage to
   the next. `/t-drive` is the one exception: named once, it chains the stages itself.
6. **Writing to the tracker** — creating, labelling, commenting, closing — happens only
   inside the stage the human invoked, for that stage's own task. Anything else is a
   proposal in the report, never an act.
7. **Checks are never weakened to pass.** Out-of-scope discoveries are reported and
   proposed as issues, not fixed in passing.

## Pipeline

| Skill | Stage |
|---|---|
| `/t-open` | Conversation → issue(s). How all work starts. |
| `/t-plan` | Pins allowed paths, risks, and checks onto the issue. Required before a protected diff. |
| `/t-work` | Branch, record, implement, checks, draft PR. Run it again on the same task to address review findings. |
| `/t-review` | Cold, read-only review; findings and a readiness verdict posted on the PR. Required before shipping a protected diff. |
| `/t-ship` | Human-confirmed squash merge. The only path to the trunk. A child of an initiative merges into the integration branch on the mechanical gate alone. |
| `/t-cancel` | Abandon a task: reason on the issue, dependents decided, PR closed, branch deleted. |
| `/t-drive` | Chains plan, work, review, and ship for one task, or for a parent's children in order into the integration branch, stopping at the parent's merge gate. |
| `/t-status` | Read-only overview of what is in flight. |
| `/t-update` | Move `t-workflow` to a newer release, as an ordinary task. |

## Naming

Task ID = issue number. Branch `wip/<id>-<slug>`. Record `docs/tasks/<id>-<slug>.md`.
PR title and squash subject `[<id>] <title>`. Commit messages imperative.

An initiative's children branch from and merge into `wip/<parent>-integration`, created
from the trunk by the first child's `/t-work`; nothing of an initiative reaches the
trunk until the parent's own PR, from that branch, does. The parent's PR carries the
children's records and one `Task:` line per child; the parent relation is the issue's
own, never a label on the child.

## Protected paths

`.t-workflow/AGENTS.md`, `.t-workflow/scripts/`, `.claude/`, `.agents/`,
`.github/workflows/`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, plus the `protected`
globs in `.t-workflow/config`. `.t-workflow/scripts/protected.sh` is the executable form.

## Checks

1. The `check` command in `.t-workflow/config`, run locally before a PR is opened,
   skipped when the whole diff is documentation (`.t-workflow/scripts/docs-only.sh`).
   No command configured → say so.
2. `git diff <trunk>...HEAD`, read against the task's scope.

CI runs the gate scripts (record, title, plan, review, blockers) from the pull
request's base branch, never its own copy; for a child of an initiative, from the
integration branch. A pull request that changes the workflow file also runs its own
copy, which can add a red result but never replace the base's. The gate checks
process, not whether a diff is honest or correct; the cold review and the
human-confirmed merge cover that. The project's build runs in its own CI; the ship
gate watches every check.

## Communication

Lead with what a change means in ordinary language before any internal term. Reports
say what actually happened; a failed check is reported as failed, never softened.

A stop that needs the human's decision is asked as a question with fixed options: the
evidence goes in the message first, then the question, through the structured question
the agent CLI offers when it has one, and as the last sentence of the message
otherwise. Nothing continues on silence. A stop that asks nothing — a `BLOCKED:` line,
red CI, a dirty tree, a rebase conflict, or a skill's own handoff to the next command —
is unchanged by this: it ends the turn with a report, and notifying on it is the
harness's job, not the workflow's.
