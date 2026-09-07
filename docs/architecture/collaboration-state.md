# Collaboration state — what `/t-status` shows, and in what order

**Status:** binding convention. Implements ADR-010 §D2/§D6/§D7 for `/t-status` (issue
#147): the single, precisely ordered classification a maintainer sees for a task whose
draft PR is in flight — never a second manual status field, and never a gate.

## Why this exists, in plain terms

Once a task has a draft PR, several things can be true about it at once: a blocker
might still be open, CI might be red, a review might be stale, a feedback pass might be
waiting on a human decision, a preview might not be built yet, and a human verification
entry might still be pending. A maintainer skimming `/t-status` needs **one** answer —
"what does this task need right now" — not five independent facts to reconcile by eye.
This document names that one answer's precedence, once, so `/t-status`'s prose, this
task's own tests, and any later reader agree on what "awaiting preview" versus
"awaiting external verification" versus "processing feedback" actually means and when
each wins over the others.

## What this is not

This is **display, never authority** (ADR-010 §D2–D4, §D7). The state named here never
blocks a merge, a review, or anything else — `/t-ship`'s own preconditions
(`check-blocker-gate.sh`, `check-review-gate.sh`, `check-verification-gate.sh`) and
`/t-drive`'s own gates are the actual mechanism that stops or allows work, and they stay
exactly as they are. `/t-status` reads the same underlying facts those gates read and
renders them for a human to skim; it never substitutes for them, and a maintainer who
wants the authoritative answer for a specific task always still runs the real gate
(`/t-ship`, `/t-review`) rather than trusting this summary as final.

## The precedence

For a task with an open PR, `.t-workflow/scripts/derive-task-state.sh` picks exactly one
state — the **first** of the following that applies, most to least urgent:

| # | Condition | State | Meaning |
|---|---|---|---|
| 1 | A blocker is still open, or was closed as cancelled | **`blocked`** | Nothing else about this task matters until the blocker is resolved or the task is re-planned around it — abandonment is not satisfaction (ADR-001 §D3). |
| 2 | CI is failing on the PR's head commit | **`checks-failing`** | A red build is decisive information; every later question (review, preview, verification) is moot until it's fixed. |
| 3 | No review has ever landed on this PR | **`awaiting-review`** | First pass — nothing has judged this diff yet. |
| 4 | A review exists, but a later commit landed after it | **`awaiting-renewed-review`** | The existing review no longer speaks for the current head — the same staleness `check-review-gate.sh` already computes by comparing `submittedAt` to the head commit's timestamp. |
| 5 | The latest (current) review reads anything other than `readiness: ready` | **`changes-requested`** | Unresolved blocker/high findings — the task needs a fix-mode pass, not a wait. |
| 6 | The record's last `## Feedback` entry is a `proposed scope expansion` still awaiting authorization | **`processing-feedback`** | A human decision (authorize + `/t-plan` re-plan) is what unblocks this, not more agent work (`docs/architecture/feedback-pass.md`). |
| 7 | A `required: true` `## Verification` entry is `pending`, `rejected`, or stale | **`awaiting-preview`** or **`awaiting-external-verification`** | See below — the two are the same underlying condition (unresolved required verification), specialized by what evidence is available. |
| 8 | None of the above | **`verified-ready-for-ship`** (the task declares at least one `## Verification` entry, all resolved and current) or **`awaiting-ship`** (the task declares none — today's unchanged classification) | Reviewed, unblocked, and nothing outstanding — the next command is `/t-ship`. |

### Splitting row 7: `awaiting-preview` vs. `awaiting-external-verification`

Both name the same fact — a required verification entry is not yet resolved for the
current commit — but a maintainer acts on them differently, so `/t-status` says which:

- **`awaiting-preview`** — the task's own preview evidence (a PR comment carrying a
  fenced JSON object matching `docs/adapters/PREVIEW.md`'s shape, for the PR's current
  head commit — see below) exists and its `status` is not `ready`. The blocker is
  mechanical: nothing for a human to exercise yet.
- **`awaiting-external-verification`** — no such preview evidence was found for the
  current commit (either the project never wires up previews at all, or one simply
  hasn't been posted for this exact commit yet — Done-when's "missing optional external
  systems do not prevent status reporting": the absence of preview evidence is never an
  error, just less specific information). The blocker is a human's judgment, not a
  build.

A **stale** required entry (`verified`/`risk-accepted` but the recorded revision no
longer matches the head commit, per `docs/architecture/verification.md`'s own formula)
falls into whichever of the two rows above its preview evidence puts it in, but its
`detail` names the staleness explicitly rather than reading identically to a
never-yet-verified entry — Done-when's "the displayed state identifies stale evidence
caused by a newer commit."

## The preview-evidence convention this task adopts

`docs/adapters/PREVIEW.md` deliberately leaves *where* a project posts preview evidence
to the project's own tooling — it names the shape, not the channel. `/t-status` needs
some already-generic, adapter-neutral channel to look in, so this task adopts the one
ADR-010 §D7 already names as an evidence channel and that `forge:pr-reviews`'s existing
contract already fetches: **a comment on the task's own PR containing a fenced JSON
object matching `docs/adapters/PREVIEW.md`'s shape** (the same shape
`.t-workflow/scripts/check-preview-evidence.sh` validates), for the PR's current head
commit (`commit` field equal to the PR's head sha). This is this task's own reading — a
convention documented here, not a retroactive requirement added to `PREVIEW.md` itself
or a new adapter operation — and a project that never posts one simply shows no preview
evidence (`previewReady: null` in `derive-task-state.sh`'s input shape), never an error.

## Where the underlying facts come from

| Fact | Source | Computed by |
|---|---|---|
| Blocked / cancelled blocker | `tracker:list-blockers` (native `blockedBy`) | `/t-status`'s existing warning, unchanged |
| CI state | `forge:pr-checks`, already in `.prs.open[].ciState` | `status-snapshot.sh`, unchanged |
| Review readiness / currency | `forge:pr-reviews` + the PR's head commit time | `status-snapshot.sh`'s extended `latestReview` (adds `readiness` and `current`) |
| Feedback classification | The task record's `## Feedback` section, on the task's own branch | `status-snapshot.sh`'s record-reading step (a `git show` of the record blob, never a checkout) |
| Verification entries + staleness | The task record's `## Verification` section (state, revision) plus the issue's `## Plan` → `verification:` list (`scope:`, matched by `role`) | `status-snapshot.sh`'s record-reading step, applying `docs/architecture/verification.md`'s exact formula |
| Preview evidence | PR comments (`comments`, already part of `forge:pr-reviews`'s contract), filtered to the head commit | `status-snapshot.sh`'s extended `.prs.open[].previewEvidence` |
| Origin | The task's own issue body `## Origin`, or its parent initiative's, per `docs/architecture/external-origin.md` | Read directly by `/t-status`'s own prose from already-fetched issue bodies — no new plumbing, the same way it already reads `## Plan` |

## Initiative summaries

An initiative's progress (`closed`/`total` sub-issues) keeps deriving from the native
`subIssuesSummary` field `/t-status` already reads — unchanged mechanism, regression-
tested by `.t-workflow/scripts/plumbing-test.sh`'s fixtures for this document
(Done-when's "initiative summaries correctly derive progress from their task
sub-issues" is a guard against this task accidentally touching that path, not new
plumbing of its own).

## Backward compatibility

A task that declares no origin, no `## Verification` entries, no `## Feedback`
entries, and has no preview evidence classifies exactly as `/t-status` did before this
task: `blocked` / `checks-failing` / `awaiting-review` / `awaiting-renewed-review` /
`changes-requested` / `awaiting-ship`. The three new states this task adds
(`processing-feedback`, `awaiting-preview`, `awaiting-external-verification`) and the
renamed `verified-ready-for-ship` only ever appear for a task that actually declares the
evidence that produces them — additive, never a relabeling of an existing
classification (Done-when's last bullet).

## Revisit triggers

- A live task's preview evidence needs a channel this convention's PR-comment reading
  cannot express (e.g. a project posts it somewhere `forge:pr-reviews` never fetches) —
  resolved by amending this document's §The preview-evidence convention, not by a
  provider-specific special case in `/t-status` itself.
- `#143`'s observer contract needs to publish a stable identifier for one of these
  states — it reads this document's vocabulary rather than inventing its own (ADR-010
  §D8).
- A real task's `## Feedback`/`## Verification` text turns out not to match
  `docs/tasks/TEMPLATE.md`'s documented shape closely enough for
  `status-snapshot.sh`'s parser to read it — a defect in that parser (or in the
  template it parses against), fixed by amending whichever is actually wrong, not by a
  narrower reading enforced only here.
