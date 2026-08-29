# AGENTS.md

This repository starts from the **delivery system** template: the workflow skills, the
constitution, and the document/record structure. The application's ratified constraints
live in `CONSTITUTION.md` §4; nothing here may assume more than §4 ratifies.

## Read first

- `CONSTITUTION.md` — the invariants. Binding on every task.
- `docs/workflow.md` — a short overview of how any change moves from idea to `main`.
  The skills in `.claude/skills/` are the instructions; the overview explains the shape
  and does not restate them.

## The pipeline

Stages are chosen per task (ADR-001). Two things are never optional: work starts from a
tracker issue, and `main` moves only by a PR a human confirmed.

**Backends are pluggable.** The skills speak in `tracker:*` (issues, labels) and
`forge:*` (PRs, CI, merges) operations; `docs/adapters/TRACKER.md` and
`docs/adapters/FORGE.md` map each operation to concrete commands for the active backend
(GitHub Issues + GitHub PRs today; a future backend adopts by editing those two files
only). Plain `git` is never abstracted.

| Skill | Stage |
|---|---|
| `/t-open` | Conversation → issue(s). How all work starts. |
| `/t-plan` | Optional; required before changing a protected surface. Pins scope, risks, and validation onto the issue. |
| `/t-work` | Branch, record, implement, check, draft PR. One invocation, in the current checkout. |
| `/t-review` | Cold-context review; findings posted on the PR. Required before shipping a protected surface. |
| `/t-drive` | Optional. Walks an initiative's children to completion on an integration branch — plan, implement, and independently review each — merging what review authorizes and excluding what fails a bounded retry; stops once, for the human's confirmation on a single PR to `main` (ADR-004). |
| `/t-ship` | Human-confirmed squash merge. Every path to `main` is a human-confirmed PR. |
| `/t-cancel` | Terminal exit: the reason recorded on the issue, every neighbour decided, then the PR closed and its branch deleted. |
| `/t-update` | For a repo generated from this template. Syncs its template-owned files to a pinned release, preserving local slots and applying pending migrations, as one ordinary task. |
| `/t-status` | Read-only pipeline overview. |

## Conventions

- **All changes go through the pipeline.** A request to change anything — code, config,
  docs — is work: open it with `/t-open` before touching any file. Never edit the tree
  outside that task's own `/t-work` session, however small the ask. Answering questions,
  reading, and designing need no task; changing files always does. The **one** exception
  is repository genesis (`CONSTITUTION.md` §3): the template's placeholder fills and the
  first commit happen by hand, because there is no tracker and no `main` to open a PR
  against yet. It expires once that commit is pushed.
- **Writing to the tracker needs the human's ask.** Creating or changing anything on the
  tracker — opening an issue, commenting, adding or removing a label, closing or
  reopening one — puts an item on the owner's tracker under the owner's name, so an agent
  does it only when the human asked for that specific thing. Noticing that an issue
  *should* exist is not being asked to create it: say what you found, propose the issue in
  the report, and wait. An agent that opens one unprompted has routed around `/t-open`,
  the pipeline's entrance, while appearing to follow it.

  The exception is **the stage the human invoked, doing what that invocation asked for**:
  `/t-open` creating the issues it was called to create and the labels they need,
  `/t-plan` writing the `## Plan` section onto the issue in its argument, `/t-work` and
  `/t-review` posting on their own task's PR, `/t-cancel` labelling and closing the
  cancelled issue, `/t-ship` closing the shipped issue after the merge gate. Where a stage
  reaches past its own task's issue — `/t-cancel` writing each neighbour's disposition,
  `/t-ship` ticking or closing a tracking issue — the human agreed to that specific write
  at that stage's gate, and the gate is what makes it asked-for.

  Everything outside that is a proposal, never an act: an issue nobody named, a label on
  an issue you merely noticed, a comment on a task that is not yours.
- **Nothing chains automatically.** Each stage ends by naming the next command and
  stopping (ADR-001). Review does not follow implementation on its own, and findings do
  not trigger their own fix pass.
- **Protected surfaces are where the ceremony stays** (`CONSTITUTION.md` §3): a plan
  before the work, an independent review before the ship. Protection comes from the paths
  a diff touches, never from a label.
- **Abandoning work is a stage, not a cleanup.** A task that will not be done is
  cancelled through `/t-cancel`: the reason and every neighbour's disposition land on the
  issue before anything is destroyed, and a cancelled blocker is abandoned, never
  satisfied (ADR-001 D3).
- Task ID = the tracker's issue number. Branch `wip/<id>-<slug>`. Record
  `docs/tasks/<bucket>/<id>-<slug>.md`, where `<bucket>` is the ID rounded down to the
  nearest 100, zero-padded to 6 digits — e.g. task 142 → `docs/tasks/000100/142-<slug>.md`
  (ADR-001 §D4).
- **A task worktree is optional.** A task that wants its own checkout — two tasks at
  once, or a long-running one — gets one by hand (`git worktree add`) or from a
  launching engine; the pipeline no longer ships a skill for it. Otherwise `/t-work`
  runs on the task branch in the current checkout. Two sessions never share a checkout.
  `/t-ship` and `/t-cancel` run from any checkout, including inside a task's own
  worktree — neither one destroys it (ADR-002), and nothing else does either: a stale
  local worktree or branch is left alone permanently (ADR-005), removed by hand
  (`git worktree remove`, `git branch -D`) only if it is ever actually in the way.
- `main` only moves by pull request. Never commit or push to `main` directly. The trunk
  name is `main` literally, throughout the skills and scripts — it is not abstracted the
  way the tracker and forge are; changing it is a find-and-replace across protected
  surfaces, done as one task.
- Commit messages: imperative, descriptive, no `wip`, no trailers. A task's
  squash-merge subject (and the draft PR title `/t-work` opens) is
  `[<id>] <title> (#<pr>)` — the bracketed issue number ties every commit in
  `git log --oneline` back to its tracker issue.
- Decisions live in `docs/adr/` (one file per decision, via PR). Anything durable settled
  in a PR thread is promoted into the record, an ADR, or `docs/architecture/` before
  merge.
- Out-of-scope work discovered mid-task is never a drive-by change: report it and
  propose an issue, which the human opens or asks you to open (see the tracker rule
  above). Exception (ADR-001): a pure typo or formatting fix in a file already inside the
  task's scope may ride along, listed in the record.

## Communication

Binding on every conversation in this project — design discussions before any skill
runs, and every stage's reports:

- **Lead with what a change means in ordinary language** — what the person using the
  system will experience, what risk was taken, what needs a human's judgment — before
  any internal terminology.
- Introduce a technical term only after its plain-language meaning. Freshly-coined
  names (event types, payload fields, block conventions) are for the artifacts, not
  the conversation.
- Section references (§N, doc names) *point* the reader somewhere; they never
  substitute for saying the thing.
- Issue bodies and task records stay precise — they are contracts for cold sessions —
  but their opening Goal/Asked paragraph follows this rule: that text is the first
  thing a human reads.

## Checks

Where a skill says "run the checks", the current check set is:

<!-- local -->
1. `./mvnw -B test` (Maven, from the repo root) — builds every module and runs its
   test suite. Added to `.github/workflows/ci.yml`.
<!-- /local -->
2. `./.t-workflow/scripts/consistency-check.sh` — cross-artifact document consistency.
3. `git diff` review against the task's declared scope (always applicable).

`.github/workflows/ci.yml` runs check 2 on every PR today, plus a `record` job asserting
that a task PR carries its task record. Add check 1 to it once the stack exists.
