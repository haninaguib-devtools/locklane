---
name: t-open
description: Open work — turn the current conversation into tracker issue(s) with the right shape (task, initiative, or initiative with a design child). The only way work starts. Use when a discussion has converged enough that goal and done-when are statable.
---

# Open work

Turn the conversation into tracker issues. Resolve every `tracker:*` operation named
below via `docs/adapters/TRACKER.md` (GitHub Issues by default). **Issues only** — no branch, no PR, no code,
no record exists after this skill runs. A PR exists only to deliver a diff; coordination
lives entirely in issues.

## Timing

Refuse (politely, with what is missing) if goal and done-when are not yet statable — the
issue bodies would be guesses. Do not wait until everything is settled either; by then
decisions are stranded in chat. The right moment: goal and done-when statable,
decomposition deferred if unclear.

## Procedure

1. Read `AGENTS.md` and `CONSTITUTION.md`.
2. **Shape judgment** — the core of this skill:
   - **Task** — deliverable fits one PR → one issue.
   - **Initiative** — several PRs → a tracking issue plus the child issues that are
     *already clear*, with dependencies.
   - **Initiative, decomposition unknown** → tracking issue plus exactly one child: a
     design task whose merged output determines the rest. Never guess a decomposition at
     open time.
3. Ensure the labels you need exist (`tracker:ensure-labels` for `initiative`, when
   opening a tracking issue; for a task issue, the classification label from step 4).
4. Create the issues (`tracker:create`, title short and imperative).

   **Classify every task issue** (never a tracking issue — an initiative is a
   coordination shape, not a bug/feature/docs kind, and already carries `initiative`):
   pick the one closest-fitting label from the classification set in
   `docs/adapters/TRACKER.md` and apply it (`tracker:label <id> <label>`) right after
   creating the issue. Never leave a task issue unlabeled — pick the closest fit rather
   than blocking on an exact match.

   **Then, for a task issue, apply any project-specific labels the tracker already
   has**, on top of the required classification label above — the two passes have
   opposite defaults. Call `tracker:list-labels`, drop every workflow-reserved label
   (`docs/adapters/TRACKER.md` §Workflow-reserved labels), and for each label left in
   the pool, apply it (`tracker:label <id> <label>`) only when its name or description
   makes its fit for this issue unambiguous. Skip the rest — this pass never guesses.
   Never invent a label name: the pool is exactly what a human already created in the
   tracker, and an issue may end up with zero, one, or several of these in addition to
   its one required classification label.

   For example, a discovered label named `spike` with the description "Exploratory
   work, no shipped output expected" fits an issue whose Goal is explicitly
   exploratory — apply it. A discovered label named `client` with no description does
   not by itself say whether it means "runs in the browser" or "customer-facing" —
   skip it rather than guess.

   Body template (omit empty sections):

   ```markdown
   ## Goal
   <one paragraph, from the conversation — self-sufficient, no chat references>

   ## Done when
   <observable criteria, machine-checkable where possible — a command, a grep, an
   exit code. A criterion only a human can judge is stated as such>

   ## Scope
   <one line: the paths/area this may touch>

   ## Non-goals
   <explicit exclusions; each deferred item gets its own issue, opened now>

   Split from: #<id>           (issues opened from another task's Non-goals)
   ```

   `Split from:` is the one relationship left as body text — GitHub has no native
   equivalent for "carved out of" (ADR-003). Write it exactly as shown, at the end of
   the body; `/t-cancel`'s spun-off sweep greps for it literally.

   **Parent and blocker are native links, set right after the issue exists** (ADR-003)
   — there is nothing to write in the body for them. A child of a tracking issue gets
   `tracker:set-parent <child-id> <tracking-id>`; each blocker named in the
   conversation gets its own `tracker:add-blocker <id> <blocker-id>` call. Do both
   immediately after `tracker:create` returns the new issue's id, same as the labelling
   step above.

   Tracking issues additionally get the `initiative` label; no Scope, and no
   hand-written task list. Each child is linked to it with `tracker:set-parent` the
   moment it is created — GitHub's own sub-issues panel and `subIssuesSummary` are the
   list and its progress from then on, so nothing here needs to stay in sync by hand.
5. **Deferred** work named in Non-goals is opened as its own issue *now*, holding the
   exclusion rationale and carrying `Split from: #<id>` — the marker that lets
   `/t-cancel` find it if the excluding task is ever abandoned. Without it that sweep has
   nothing to query and degrades to memory.

   Only *deferred* work earns an issue: something the project intends to do later. A
   Non-goal that is simply a boundary — "does not touch billing", "no migration here" —
   is prose in this issue and nothing more. Opening an issue per boundary would make a
   four-exclusion task cost five issues and tax exactly the cheapness the pipeline
   depends on. If it is unclear which kind an exclusion is, ask rather than minting.
6. Report — in plain prose per AGENTS.md §Communication, saying what each issue means in
   ordinary language before any internal terminology: the issue map (numbers, titles,
   dependencies), assumptions made, and the next step. That next step is `/t-plan <id>`
   when the task will touch a protected surface (`CONSTITUTION.md` §3) or its scope needs
   pinning down, and `/t-work <id>` otherwise.

## Rules

- Issues only. Never create a branch, record, or PR here; never write application files.
- Issue bodies must be self-sufficient — a fresh session with no conversation context
  must be able to work the task from the body alone.
- After work starts on a task, intent changes in its record, never in the issue body.
- One deliverable per issue. Three headings of scope = three issues.
- Two working levels only (initiative → task). A child that needs children means the
  initiative should be split.
