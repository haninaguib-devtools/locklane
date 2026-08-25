---
name: t-review
description: Independently review a task's PR diff against its record, scope, and the constitution, in cold context; post findings as a PR review and state readiness. Required before shipping a protected surface. Use to review, verify, or check readiness of a task.
# Narrows the tool set to match the read-only promise below: no Edit, no Write, so the
# skill cannot modify the tree through those. Bash is still required — for the diff
# reads, the check commands, and posting the review — and remains a write channel in
# principle, so the prose rule below is what governs; this line removes the easy paths.
#
# `Agent` is here because Procedure step 1 *requires* it: an implementing session obtains
# isolation by spawning a read-only subagent. Omitting it would make that branch
# unreachable and force every warm review to stop — which, in a phase where nearly every
# path is protected, is every review. If a harness offers no such tool, step 1's third
# branch already covers it: check protection, and stop rather than review warm.
allowed-tools: Read, Grep, Glob, Bash, Agent
---

# Review a task

One bounded stage producing findings and a readiness verdict.

Optional in general, **required before `/t-ship` whenever the PR touches a protected
surface** (`CONSTITUTION.md` §3) — ADR-001. A human may invoke it on any task; a
`not-ready` verdict blocks shipping whether or not the review was required.

## Isolation

An independent review is the point: a reviewer that inherits the implementer's reasoning
re-derives conclusions instead of testing them.

- **Preferred:** a fresh session, or a read-only subagent. Everything needed is in the
  tracker, on the forge, and in the diff (resolve `tracker:*` / `forge:*` operations via
  `docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md`; GitHub by default).
- Reviewing inside the implementing session is acceptable only for a change so small
  that the spawn costs more than the read — and never for a protected surface.
- The reviewer is **read-only**: it posts findings; it fixes nothing.

## Procedure

The argument is the task id (`/t-review 154`); the steps below name `<pr>`. **Resolve it
first** with `forge:pr-find-by-task <id>`, which matches the head branch `wip/<id>-*` —
the id lowercased, `PROJ-142` → `proj-142` (ADR-001 §D4) — across all
states, names the PR. Exactly one → that is `<pr>`. None → there is nothing to review;
say so and name `/t-work <id>`. More than one → stop and report every candidate. This
matters more here than anywhere else: a cold session starts holding the id and nothing
else.

1. **Obtain isolation before reading anything.** This is a step, not a preference — the
   Isolation section above is the rule, and here is how to satisfy it:

   - **This session did not implement the task** (fresh session, or the human invoked
     `/t-review` cold) → you are already isolated. Record `isolation: fresh session`.
   - **This session implemented the task** → spawn a read-only subagent to perform the
     whole review and report its findings back; everything it needs is the task id, the
     tracker, the forge, and the diff. Record `isolation: subagent`.
   - **A subagent is unavailable** → determine protection first (`forge:pr-files` through
     `bash scripts/protected-paths.sh --stdin`). On a **protected surface, stop**: say a
     cold review is required, that this session cannot provide one, and ask for a fresh
     session. Reviewing here anyway produces a verdict `/t-ship` will reject. Otherwise
     continue and record `isolation: same session (<why the change was small enough>)`.

   Deciding this first is what keeps the isolation line honest: written at the end, it
   describes whatever happened to happen.

2. Read `AGENTS.md`, `CONSTITUTION.md`, the issue (Goal, Done when, Scope, any Plan), the
   task record in the diff, and any design doc the issue names.
3. Inspect the complete diff: `forge:pr-diff <pr>` (or `git diff main...HEAD` on the
   branch). Also `git status` — an uncommitted change on the branch is itself a finding:
   the PR carries only commits. Same for a **committed but unpushed** one: compare
   `git rev-parse HEAD` with the PR's head (`forge:pr-view <pr>`), because reviewing a
   local diff the PR does not contain is reviewing something that will not merge.
4. Check, for **code and documents alike**:
   - **Scope** — every changed path inside Allowed paths or the issue's Scope line. Drift
     is a finding. An issue carries exactly one `## Plan` section, and it governs:
     `/t-plan` replaces the section on a re-plan rather than appending a second, so the
     one you read is current by construction. Two sections is itself a finding — the
     scope cannot be judged against a plan that does not have a single answer.
   - **Record honesty** — does the record describe this change truthfully, including
     decisions and deviations? A silent deviation is a finding.
   - **Constitution** — no conflict with `CONSTITUTION.md`; no weakened guardrail.
   - **Unexplained removals** — behavior, content, or tests that disappeared without the
     issue authorizing it.
   - **Promotion** — anything durable settled in the PR thread is in the record, an ADR,
     or the docs; threads are not storage.
5. For a **document deliverable** (design doc, ADR), additionally review for:
   - **Consistency** — contradictions with the constitution, accepted ADRs, or other
     architecture docs.
   - **Ambiguity** — could two reasonable implementers read it differently?
   - **Completeness** — are the known hard cases addressed rather than absent?
   Do **not** judge whether the design is *right* — that is the human's approval.
6. Run the checks tagged `review` or `either` in the plan, or the set in `AGENTS.md`
   §Checks when there is no plan. A failing check is a finding at the severity its
   consequence deserves. Four things are **blocker or high by construction**, never
   medium or low: a check that failed, behavior or content removed without the issue
   authorizing it, a changed path outside the task's declared scope, and a changed path
   on a protected surface whose issue carries no `## Plan` section — protection follows
   the paths the diff actually touches, so a task that grew onto one needs
   `/t-plan <id>` before it can ship. Decide protection by running
   `bash scripts/protected-paths.sh --stdin` over the changed paths (`CONSTITUTION.md`
   §3 in executable form), not by reading the list by eye — exit 0 = protected, 1 = none,
   2 = nothing was checked, which is a finding in itself rather than a clean result. Only
   blocker and high findings hold the verdict open (step 7), so grading one of these down would let it
   through — that is weakening a guardrail to make work pass, which `CONSTITUTION.md`
   §1.5 forbids.

   For a document review, also run `scripts/consistency-check.sh`; its failures are
   findings, and semantic consistency remains this skill's judgment.
7. Post the findings as a PR review, each stated in plain prose per AGENTS.md
   §Communication — what is wrong and what it would break, in ordinary language before
   any internal terminology, via `forge:pr-review <pr>`.

   **Open the body with one line stating how isolation was obtained** — `isolation: fresh
   session`, `isolation: subagent`, or `isolation: same session (<why the change was small
   enough)`. Nothing can verify this after the fact: the comment a cold reviewer posts and
   the one a warm reviewer posts are byte-identical, so the claim is the only record that
   independence was considered at all. `same session` on a protected surface is a
   contradiction — that combination is not permitted (see Isolation above), and writing it
   down is what makes the violation visible instead of invisible.

   Findings ordered blocker / high / medium / low, each with evidence and a location.
   End the body with an explicit verdict line:

   `readiness: ready` — no blocker or high findings and no unexplained removals.
   `readiness: not-ready` otherwise, naming the next step (normally `/t-work <id>` in fix
   mode).

   **Only blocker and high findings hold the verdict open.** Medium and low findings are
   posted in full, with the same evidence and locations, and the verdict still reads
   `ready`. They are handed to the human, who decides whether to fix them now, open
   them as their own issues, or accept them. This scopes what a *verdict* blocks on; it
   narrows nothing the review inspects and removes no check.

   **A review posts findings; it never turns them into issues.** The PR review is the
   artifact this stage was invoked to write, and it is the whole of what this stage may
   write to the tracker (AGENTS.md §Conventions). A finding that deserves its own issue —
   including anything noticed outside this diff — is named in the review body as an issue
   you *recommend*, for the human to open or to ask for. Opening it here would put an item
   on the owner's tracker that nobody asked for, and would let a reviewer file work around
   `/t-open`.

   **A pending human check does not make a review `not-ready`.** A plan's `human_checks`
   are the judgments deliberately assigned to a person because no command settles them, so
   a reviewer can never discharge one and blocking on them would strand tasks in review
   permanently. Instead **restate them in a section headed `## Pending human checks`,
   immediately above the verdict line**, each naming what the human must judge and
   where in the diff to look. Write the heading followed by `none` when the plan has none:
   the section is always present, because an omitted section cannot be told apart from a
   forgotten one. Restate them on every pass; a check stays listed until the human
   says they have settled it.

   That section is **the source `/t-ship` reads** before asking for merge confirmation, so
   state the checks plainly enough to be acted on by someone who is not re-reading the
   diff. `/t-ship` treats a missing section as unknown, never as `none`.

   **Do not hunt.** When the change does what the issue asked, stays inside its scope,
   removes nothing the issue did not authorize, and the checks pass, say `readiness:
   ready` and say it plainly. A review that finds nothing blocking is a normal outcome,
   not evidence of a shallow review; manufacturing a finding to justify a round trip
   costs the human real time and is itself a defect. Report honestly in the other
   direction too — this is not licence to soften a genuine blocker into a medium.

8. **Stop.** Report the findings and end the stage (ADR-001): do not fix, and do not
   trigger a fix pass. The next step is the human's — `/t-work <id>` to address
   findings, or `/t-ship <id>` when the verdict is ready.

9. A pass following a fix pass is **scoped**: read the previous review
   (`forge:pr-reviews <pr>`), verify the named findings at their locations, inspect what the fixes touched, and re-run only the checks they falsify.
   Post a fresh review comment; earlier findings absent from the latest verdict are
   resolved.

## Rules

- Never call a skipped or blocked check passed.
- Do not approve work that matches the issue but conflicts with the constitution.
- Do not widen scope, do not fix, do not commit, and never mark the PR ready or merge.
- Do not create or change tracker artifacts — no new issues, no labels, no issue comments,
  no closing or reopening. The findings go in the PR review and nowhere else.
