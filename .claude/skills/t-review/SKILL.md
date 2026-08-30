---
name: t-review
description: Independently review a task's PR diff against its record, scope, and the constitution, in cold context; post findings as a PR review and state readiness. Required before shipping a protected surface. Use to review, verify, or check readiness of a task.
allowed-tools: Read, Grep, Glob, Bash, Agent
---

# Review a task

One bounded stage producing findings and a readiness verdict. Optional in general,
**required before `/t-ship` whenever the PR touches a protected surface**
(`CONSTITUTION.md` §3, ADR-001). A human may invoke it on any task; a `not-ready`
verdict blocks shipping whether or not the review was required.

## Isolation

An independent review is the point: a reviewer that inherits the implementer's
reasoning re-derives conclusions instead of testing them. **Preferred:** a fresh
session, or a read-only subagent — everything needed is in the tracker, on the forge,
and in the diff (resolve `tracker:*` / `forge:*` operations via
`docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md`; GitHub by default). Reviewing
inside the implementing session is acceptable only for a change so small that the spawn
costs more than the read — never for a protected surface. The reviewer is **read-only**:
it posts findings, it fixes nothing.

## Procedure

The argument is the task id (`/t-review 154`); the steps below name `<pr>`. **Resolve
it first** with `forge:pr-find-by-task <id>`, matching head branch `wip/<id>-*` across
all states. Exactly one → that is `<pr>`. None → nothing to review, say so and name
`/t-work <id>`. More than one → stop, report every candidate — a cold session starts
holding only the id, so this matters more here than anywhere else.

1. **Obtain isolation before reading anything** — deciding this first is what keeps the
   isolation line honest; written at the end, it describes whatever happened to happen.
   **This session did not implement the task** (fresh session, or the human invoked
   `/t-review` cold) → already isolated, record `isolation: fresh session`. **This
   session implemented the task** → spawn a read-only subagent to perform the whole
   review and report its findings back; everything it needs is the task id, the
   tracker, the forge, and the diff — record `isolation: subagent`. **A subagent is
   unavailable** → determine protection first (`forge:pr-files` through `bash
   .t-workflow/scripts/protected-paths.sh --stdin`); on a **protected surface, stop** and ask for a
   fresh session — reviewing here anyway produces a verdict `/t-ship` will reject.
   Otherwise continue and record `isolation: same session (<why the change was small
   enough>)`.
2. Read `AGENTS.md` and `CONSTITUTION.md` — unless this review's `isolation:` (step 1
   above) is `same session` on a `/t-drive` run whose Phase 0 already read them for the
   whole run, in which case that read already covers this one. **`isolation: fresh
   session` and `isolation: subagent` always read both themselves** — a cold session or a
   spawned subagent is a separate context that has read nothing this run, driven or not
   (Isolation above); this condition is deliberately narrower than the other chained
   stages'. A standalone invocation with no driving session always reads both itself.
   Then fetch everything else in one call:
   `.t-workflow/scripts/review-snapshot.sh <id> <pr>` — it returns the issue (body,
   labels, parent), the complete PR diff, the PR's view (head sha, changed files),
   and local git state (`git status`, `git rev-parse HEAD`) in a single JSON blob,
   replacing what steps 2-3 used to fetch one call at a time. Read the issue's Goal,
   Done when, Scope, and any Plan section from its `.issue.body`; read the task record
   in `.diff`; read any design doc the issue names.
3. Inspect the complete diff (`.diff`). `.local.clean == false` — an uncommitted
   change on the branch — is itself a finding, the PR carries only commits. Same for a
   **committed but unpushed** one: `.local.head != .pr.headRefOid` means a local diff
   the PR does not contain, which will not merge.
4. Check, for **code and documents alike**: **scope** (every changed path inside
   Allowed paths or the issue's Scope line — drift is a finding; an issue carries
   exactly one `## Plan` section and it governs, so two is itself a finding);
   **record honesty** (does the record describe this change truthfully, decisions and
   deviations included — a silent deviation is a finding); **constitution** (no
   conflict with `CONSTITUTION.md`, no weakened guardrail); **unexplained removals**
   (behavior, content, or tests gone without the issue authorizing it); **promotion**
   (anything durable settled in the PR thread is in the record, an ADR, or the docs —
   threads are not storage).
5. For a **document deliverable** (design doc, ADR), additionally review for
   **consistency** (no contradiction with the constitution, accepted ADRs, or other
   architecture docs), **ambiguity** (could two reasonable implementers read it
   differently?), **completeness** (are the known hard cases addressed?). Do **not**
   judge whether the design is *right* — that is the human's approval.
6. Run the checks tagged `review` or `either` in the plan, or the set in `AGENTS.md`
   §Checks when there is no plan (for a document, also `.t-workflow/scripts/consistency-check.sh`;
   its failures are findings, semantic consistency stays this skill's judgment).

   **A check tagged `either` (or, with no plan, one named in `AGENTS.md` §Checks) may be
   reported as already passing, without running it again, only when the PR's
   `## Checks run` section (`/t-work` Phase 3 step 5) names that exact command at a
   commit sha equal to `.pr.headRefOid`** — the head this review is reading, already in
   the snapshot from step 2. Anything short of an exact match — no such section, a
   differently-worded command, or a sha that is not the current head (a push landed
   after the note was written, including a fix-mode push that rewrote the section for a
   newer commit) — leaves the reuse condition unproven: **run the check yourself**,
   exactly as if no plan named it reusable at all. A check tagged `review`-only is never
   a candidate — `/t-work` was never going to run it, so there is nothing to reuse.

   A failing check is a finding at the severity its consequence deserves. Four things are
   **blocker or high by construction**, never medium or low: a check that failed,
   behavior or content removed without the issue authorizing it, a changed path outside
   the task's declared scope, and a changed path on a protected surface whose issue
   carries no `## Plan` section — decide protection with `bash
   .t-workflow/scripts/protected-paths.sh --stdin` over the changed paths (`.pr.files`
   from the snapshot), not by eye (exit 0 =
   protected, 1 = none, 2 = nothing checked, itself a finding). Grading one of these
   down to let it through weakens a guardrail to make work pass, which
   `CONSTITUTION.md` §1.5 forbids.
7. Post the findings as a PR review, each stated in plain prose per AGENTS.md
   §Communication — what is wrong and what it would break, in ordinary language before
   any internal terminology — via `forge:pr-review <pr>`.

   **Open the body with one line stating how isolation was obtained** — `isolation:
   fresh session`, `isolation: subagent`, or `isolation: same session (<why the change
   was small enough)`. The claim is the only record independence was ever considered,
   since a cold reviewer's comment and a warm one's are byte-identical otherwise;
   `same session` on a protected surface is a contradiction (see Isolation above) —
   writing it down makes the violation visible instead of invisible.
   **List every check from step 6, each line naming which it was:** a reused one as
   `reused — ran by /t-work at commit \`<sha>\`: \`<command>\` — <PASS/FAIL>`, one this
   review ran itself as `ran by this review: \`<command>\` — <PASS/FAIL>`. The two
   phrasings are never interchangeable — nobody reading the review may mistake a reused
   result for one this pass actually verified.
   Findings ordered blocker / high / medium / low, each with evidence and a location.
   End with an explicit verdict line: `readiness: ready` — no blocker or high findings
   and no unexplained removals; `readiness: not-ready` otherwise, naming the next step
   (normally `/t-work <id>` in fix mode). **Only blocker and high findings hold the
   verdict open** — medium and low are posted in full, same evidence and locations, and
   the verdict still reads `ready`; the human decides whether to fix them now, open
   them as their own issues, or accept them.
   **A review posts findings; it never turns them into issues** — the whole of what
   this stage may write to the tracker (AGENTS.md §Conventions). A finding that
   deserves its own issue — including anything noticed outside this diff — is named in
   the review body as a *recommendation* for the human to open or ask for; opening it
   here would let a reviewer file work around `/t-open`.
   **A pending human check does not make a review `not-ready`.** A plan's
   `human_checks` are judgments deliberately assigned to a person because no command
   settles them, so a reviewer can never discharge one. Instead **restate them in a
   section headed `## Pending human checks`, immediately above the verdict line**, each
   naming what the human must judge and where to look — heading followed by `none` when
   the plan has none (always present: an omitted section cannot be told apart from a
   forgotten one). Restate on every pass; a check stays listed until the human says it
   is settled. This is **the source `/t-ship` reads** before the merge gate, so state
   checks plainly enough to act on without re-reading the diff — `/t-ship` treats a
   missing section as unknown, never as `none`.
   **Do not hunt.** When the change does what the issue asked, stays in scope, removes
   nothing unauthorized, and the checks pass, say `readiness: ready` plainly — a review
   that finds nothing blocking is normal, not evidence of a shallow one. Report
   honestly the other way too: this is not licence to soften a genuine blocker.
8. **Stop.** Report the findings and end the stage (ADR-001): do not fix, do not
   trigger a fix pass. The next step is the human's — `/t-work <id>` to address
   findings, or `/t-ship <id>` when the verdict is ready.
9. A pass following a fix pass is **scoped**: read the previous review
   (`forge:pr-reviews <pr>`), verify the named findings at their locations, inspect
   what the fixes touched, re-run only the checks they falsify. Post a fresh review
   comment; earlier findings absent from the latest verdict are resolved.

## Rules

- Never call a skipped or blocked check passed.
- Do not approve work that matches the issue but conflicts with the constitution.
- Do not widen scope, fix, commit, or mark the PR ready or merge.
- Do not create or change tracker artifacts — no new issues, labels, comments, closing,
  or reopening. Findings go in the PR review and nowhere else.
