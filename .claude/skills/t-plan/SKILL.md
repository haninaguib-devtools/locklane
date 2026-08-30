---
name: t-plan
description: Plan a task — pin its precise allowed-paths scope, risks, and validation split onto the issue before implementation. Required before changing a protected surface. Use after t-open, before t-work.
---

# Plan a task

Sharpen an existing issue into a bounded plan. Do not implement anything.

The argument names the issue (`/t-plan 152`). Planning is optional in general (ADR-001)
and **required before any change to a protected surface** (`CONSTITUTION.md` §3). If
planning a task reveals it will touch one, say so — the plan is now mandatory, not
optional, and `/t-work` will refuse without it.

## Procedure

1. Read the issue (`tracker:view <id>` — resolve every `tracker:*` operation via
   `docs/adapters/TRACKER.md`; GitHub Issues by default) and its tracking issue if any.
   Read `AGENTS.md` and `CONSTITUTION.md` too — unless this plan is `/t-drive` chaining
   this stage in the same session whose Phase 0 already read them for the whole run, in
   which case that read already covers this one. A standalone invocation, with no
   driving session, always reads both itself.
2. Inspect the repository enough to plan honestly — relevant files, existing structure.
   Do not load everything by default.
3. **Template-owned-file check.** Check whether `.template-manifest.json` exists at the
   repo root — that is what marks a pinned template consumer
   (`docs/architecture/manifest.md`); this repository itself never carries one. When it
   does, and a candidate target sits inside a template-owned file
   (`.t-workflow/scripts/template-owned-paths.sh --list`), do not decide where the
   content lands by reasoning about the file's own section prose ("this reads like
   Conventions, put it there"). Read `docs/architecture/local-slots.md` and resolve the
   target against its named `<!-- local -->` … `<!-- /local -->` slots instead — those
   are the only regions a consumer edit may land in; everything else is hashed into the
   manifest and fails `.t-workflow/scripts/check-manifest.sh` after the fact. If the
   change fits inside a named slot, that slot's location becomes the Allowed path. If it
   does not — no slot covers it — the plan must not target that file: say so in the
   report and name the slot that would be needed (or that none exists), rather than
   writing an Allowed path outside one.
4. Produce the plan and **write it to the issue body** (`tracker:edit-body <id>`),
   preserving everything else there. On a first plan that means appending the section;
   on a re-plan it means replacing it, per the rule below. Either way, changing only
   this section is the one edit to an issue body that is expected after its PR opens —
   `/t-status` exempts it; anything else changed at the same time is the intent drift
   that warning exists to catch.

   **When the issue already has a `## Plan` section, replace it — wholesale.** This
   happens on a re-plan: `/t-work` Phase 3 and `/t-ship` precondition 1 both send a task
   back here when its diff grew onto a protected surface the old Allowed paths never
   covered. Write the new section from scratch against what the task is now, and delete
   the old one. **An issue carries exactly one `## Plan` section, always**, so the three
   skills that read it never need a rule for which of two binds.

   Nothing is lost by replacing, but only because the record catches it. Why the scope
   changed is history, and history belongs in the task record's Deviations — the issue
   states what is true now. **Quote the previous Allowed paths verbatim in your report**,
   alongside the new ones and the reason they changed; `/t-work` step 6 requires exactly
   that text in the record before work resumes. Without it the old scope survives only in
   the tracker's edit history, which `CONSTITUTION.md` §1.3 says is not where anything
   binding may live.

   The section:

   ```markdown
   ## Plan
   ### Allowed paths
   <precise globs/paths the diff may touch — binding on t-work, checked by t-review>

   ### Risks / constraints
   <what could go wrong; constitution rules that apply>

   ### Validation
   agent_checks:
     - <runnable command or concrete procedure> — stage: implementation|review|either
       — proves: <what a pass demonstrates>
   human_checks:
     - <judgments cheaper or more reliable for a human>
   ```

5. **Scope-overlap check.** List every open issue and compare Allowed paths and Scope
   lines, using `tracker:list-open` — its contract requires a complete scan. A truncated
   list reports "no overlap" because it never looked, so a result that hits the backend's
   page limit is an incomplete scan: say so and paginate rather than concluding anything
   from it. A non-empty overlap
   is not automatically a refusal (some overlaps are sequential by a blocked-by
   dependency), but it must be named in the plan so a person decides at plan time
   rather than at merge-conflict time.
6. Report — in plain prose per AGENTS.md §Communication, saying what the plan means in
   ordinary language before any internal terminology: validation ownership, open
   questions, and whether the task is ready for `/t-work`.

## Rules

- Tag every agent check with its stage; untagged checks get run twice by two stages.
- **State every done-when criterion machine-checkable where possible** — a runnable
  command whose exit code or output settles it. A criterion that genuinely cannot be
  machine-checked is not a failure: assign it to `human_checks` with one sentence on why
  (verification only possible post-merge is the classic case).
- The done-when acceptance check belongs to implementation, not review — review confirms
  behavior, it must not be the stage that first discovers it.
- If the issue is incomplete, fix the issue or stop with a clear question. Never hide a
  gap inside the plan.
- A re-plan replaces the `## Plan` section and touches nothing else in the body. Editing
  Goal, Done when, Scope or Non-goals here is intent drift — once work has started, task
  intent changes in the record rather than the issue body (`docs/tasks/README.md`,
  `/t-open` §Rules), and this section is that rule's one exception.
- Do not implement, and do not create branches or PRs here.
