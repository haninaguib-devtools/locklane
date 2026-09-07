# Feedback pass — shape and rules

**Status:** binding convention. Implements ADR-010 §D6's concrete mechanism (issue
#145): feedback that arrives after implementation begins — a contributor's preview
comment, a maintainer's manual QA finding, an observer-relayed note — returns to the
task's own existing draft PR through an explicit `/t-work` mode, never applied
directly and never opened onto a second branch or PR.

## Why this exists, in plain terms

Once a draft PR exists, a task rarely stays quiet: a contributor tries the preview and
finds something off, a maintainer runs a manual check and spots a gap, someone pastes
a screenshot in a comment. None of that is scope until a human decides it is. This
document names the pass that turns "somebody said something" into "here is what
changed and why," without letting the comment itself quietly redraw the task's
boundaries.

## Invoking a feedback pass

`/t-work <id> feedback <reference> [<description>]` — the literal word `feedback`
after the issue id switches `/t-work` into this mode (Phase 1 step 5,
`.claude/skills/t-work/SKILL.md`); anything else is ordinary Normal/Fix-mode
invocation, unchanged.

- **`<reference>`** — a durable pointer to where the feedback lives: an external URL
  (a preview tool, an external tracker, a Proposarium comment), or a permalink to a
  comment already on the task's own PR or issue. Recorded verbatim; never fetched or
  executed (§Evidence is recorded, never fetched, below).
- **`<description>`** *(optional, but required when `<reference>` is not itself
  something `/t-work` can read)* — what the reference says, in the human's own words.
  A comment already on the task's own PR or issue is forge-native data `/t-work`
  already reads elsewhere in this skill (the same way Phase 1 step 5 already reads
  `forge:pr-reviews`); when `<reference>` names one, `/t-work` may read its text
  directly instead of asking the human to retype it. Anything else — an external URL,
  a system outside the forge — is an unauthenticated pointer (ADR-010 §D7): `/t-work`
  cannot act on content it never reads, so `<description>` carries the substance.
- With no `feedback` argument, `/t-work` behaves exactly as it always has — this mode
  is additive, never a default.

## Evidence is recorded, never fetched (ADR-010 §D7)

`<reference>` is copied into the record exactly as given. No step in this mode
resolves, fetches, or executes it, and its reachability is never a pass/fail signal
for anything — the same rule `docs/architecture/external-origin.md` already applies to
an origin's `url`. A stale or dead reference leaves the entry exactly as informative
as a live one: a pointer for a human to follow, never a gate input.

## Classifying before touching a file

Before any file changes, `/t-work` reads the issue's Goal, Done when, Scope, and its
`## Plan` (Allowed paths) and classifies what the feedback asks for as exactly one of:

| Classification | Meaning | What happens |
|---|---|---|
| **Clarification** | Resolves an ambiguity; no behavior or content needs to change. | Recorded in the task record's `## Feedback` section as `clarification`, with what was clarified. No code change, no new commit required — a pass that classifies every item as clarification may end here. |
| **Defect** | Something already in scope is wrong — a bug in behavior the task already implements. | Implemented like any other Phase 2 work, strictly within the existing Allowed paths. |
| **In-scope adjustment** | A legitimate change the existing Allowed paths and Done-when already cover. | Implemented like any other Phase 2 work, strictly within the existing Allowed paths. |
| **Proposed scope expansion** | Addressing it would touch a path outside the Allowed paths, or change what Done-when requires. | **Stops before any file is touched.** Reported as a proposed expansion, exactly like any other out-of-scope discovery (`.claude/skills/t-work/SKILL.md` Phase 2's existing rule) — it is implemented only after the human explicitly authorizes it and `/t-plan <id>` re-plans the task, the same path any other scope growth already takes (`/t-work` Phase 1 step 6's "resuming after a re-plan"). |

A pass may see several feedback items at once, each carrying its own classification —
one being a proposed scope expansion never blocks the others from proceeding, and
`/t-work` reports each separately rather than folding them into one verdict.

**This is a judgment call, not a new gate script.** `/t-work` already refuses to touch
a path outside the Allowed paths (Phase 2's existing binding rule); classifying
"proposed scope expansion" states the *reason* a stop is happening before the fact,
where the existing rule would otherwise catch the same drift only after a diff already
existed. No new enforcement mechanism is built here — the existing Allowed-paths
enforcement is what actually blocks a defect/adjustment pass from wandering, and
`/t-review`'s existing scope check (`.claude/skills/t-review/SKILL.md` step 4) catches
anything that slipped through.

## What changes, and what doesn't, once classification clears

A `defect` or `in-scope adjustment` pass proceeds through `/t-work`'s ordinary Phase 2
and Phase 3 exactly as Fix mode already does: same branch, same draft PR, checks
re-run, diff reviewed, commit pushed. Nothing about *how* the work happens is new —
only the entry point (an evidence reference and a classification) and what lands in
the record (below) are.

## The record: `## Feedback`

`docs/tasks/TEMPLATE.md` carries a `## Feedback` section, always present — "none" when
no feedback pass has run yet, the same convention `## Origin` and `## Verification`
already use (`docs/tasks/README.md`). Each pass appends one entry:

```
## Feedback
- reference: <the durable URL or comment permalink, verbatim>
  source: <who or what surfaced it — a contributor, a maintainer, an observer-relayed
    note>
  classification: clarification | defect | in-scope adjustment | proposed scope
    expansion
  response: <what changed in response, or "none — clarification only", or, for a
    proposed scope expansion, "stopped; awaiting human authorization and /t-plan">
  by: <who ran the pass> — date: <when>
```

A record with no `## Feedback` entries — every task before this convention existed,
and any task nobody has sent feedback to since — reads "none" and needs no migration
(`docs/tasks/README.md`).

## Invalidation: no new mechanism

A feedback pass that reaches Phase 3 pushes a new commit exactly the way Fix mode
already does, so it inherits both existing invalidation paths for free — neither is
changed or duplicated here:

- **Review.** `.t-workflow/scripts/check-review-gate.sh` already compares a review's
  `submittedAt` against the head commit's timestamp; any review that predates the new
  commit a feedback pass just pushed already fails that gate on its own. A feedback
  pass needs no review-invalidation step of its own.
- **Human verification.** `/t-work` Phase 3 step 2's existing verification-invalidation
  judgment (`docs/architecture/verification.md`) already runs against *any* new commit
  on the task's branch, feedback-mode or not: an affected `verified`/`risk-accepted`
  entry is flipped back to `pending` and a Deviations note explains why, exactly as it
  already does for Fix mode.
- **Checks.** Phase 3 step 1 re-runs the checks tagged `implementation`/`either`, and
  step 2's "an edit here invalidates step 1's result" rule already applies to whatever
  a feedback pass's own diff touches — the same generic re-run rule every `/t-work`
  pass already follows, not a feedback-specific one.

## The draft PR stays the one implementation surface

A feedback pass never opens a second branch or a second PR (issue #145's own
non-goal): it resumes the task's *existing* draft PR on its *existing* branch, the same
target Fix mode already pushes to. A task with no PR yet has nothing for a feedback
pass to resume — `/t-work` stops and says so rather than opening one under a different
path than Phase 3 already does.

## Relationship to Fix mode

Fix mode resumes work in response to `/t-review`'s own findings; a feedback pass
resumes work in response to evidence from outside that loop — a contributor, a preview,
manual QA. They share the same mechanics (same branch, same PR, checks re-run, `##
Checks run` rewritten wholesale for what this pass actually re-ran) because both are
simply `/t-work` continuing the same task under a new reason to look at it again. A
task may see either mode, both in sequence, or neither.

## Non-goals (unchanged from issue #145)

This mode never accepts an external suggestion automatically, never lets a comment
silently change scope, never opens a second implementation branch or PR for a
feedback round, and never replaces `/t-review`'s independent review.

## Revisit triggers

- A feedback source needs a reference shape this document's two-field grammar
  (`<reference>` plus an optional `<description>`) cannot express — resolved by
  amending this document, not by a source-specific special case in the skill.
- `#146`'s preview adapter contract or `#147`'s `/t-status` surface need to read a
  feedback pass's classification or outcome directly — they read this document's
  shape rather than inventing their own.
- A live pass finds the four-way classification ambiguous on a real task — see ADR-010's
  own revisit triggers; a boundary this document draws unworkable in practice is a
  defect in this document, fixed by amending it, not by a narrower reading enforced
  only in the skill.
