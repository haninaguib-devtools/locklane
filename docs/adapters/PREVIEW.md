# Preview adapter contract

**Status:** binding convention. Implements ADR-010 §D2/§D7 for the "project" party
(issue #146): the generic shape a consuming project's own tooling produces so a human
can exercise a reviewable version of a draft task branch, without this repository ever
assuming a deployment platform, a container system, or a cloud provider.

## Why this exists, in plain terms

A reviewer can read a diff. A contributor or a domain expert usually cannot judge
whether a change is *right* from the diff alone — they need to click through a running
version of it, install a built package, or flash an image, and say what they saw. This
document names the two small facts a project's own preview tooling needs to hand
back so that click-through can happen and be trusted: **what exact commit** it built,
and **what a human gets** to exercise it. Everything about how that gets built —
container, cloud, install script, USB stick — is the project's own choice; t-workflow
never assumes one.

## What this is not

This is a **contract**, not a service. t-workflow ships no preview provider, no
deployment code, and no CI job that builds one. A project that wants previews
implements this contract in its own tooling and its own local workflow slots
(§Where a project wires this in, below); a project that never wants previews needs to
do nothing here at all (Non-goals: previews are never required).

This is also **evidence, never authority** (ADR-010 §D7). A preview's reported status is
something a human reads on the way to a judgment — it can never itself open, label,
approve, or merge anything, and nothing in this document may be read as licensing that.
See §Preview success vs. human verification success, below, for the boundary this most
directly protects.

## What a preview provider is handed

Whatever raises a preview needs exactly four things to identify what it is previewing —
the task, not some later or earlier state of it:

| Field | Meaning |
|---|---|
| `task` | The task's tracker issue number (`docs/adapters/TRACKER.md`'s task ID). |
| `branch` | The task's branch, `wip/<task>-<slug>` (`AGENTS.md` §Conventions). |
| `commit` | The exact commit sha being previewed — never "the branch's current tip" as a moving target; a later push is a different `commit` value, not an update to this one. |
| `pr` | The draft PR's number and URL (`docs/adapters/FORGE.md`'s `forge:pr-view`), so a preview can link back to the review surface it supports. |

A project's tooling reads these four from the branch/commit it is asked to build and the
open PR `forge:pr-find-by-task` resolves for that task — t-workflow names the fields; it
neither computes nor transmits them for the project.

## The evidence a preview reports back

One **preview-evidence** object per attempt, always naming the exact commit it speaks
for:

| Field | Meaning |
|---|---|
| `commit` | The exact commit sha this evidence is for (ties evidence to a build, never to "the branch" in general — see §Evidence is tied to an exact commit). |
| `type` | Free text naming the kind of preview: `web`, `package`, `binary`, `image`, `environment`, `manual`, or any other name a project needs (§Non-web previews) — never a closed enum a template change would be needed to extend. |
| `status` | One of exactly four states (§States, below): `pending`, `ready`, `failed`, `expired`. |
| `review_url` | Optional. Where a human goes to exercise a web-based preview. Absent for a preview a URL cannot represent (a downloadable package, a flashed image). |
| `instructions` | Optional. Plain-language steps for a human to exercise the preview when a URL alone is not enough, or is not applicable at all — "download the `.tgz` from `review_url` and run `npm install ./pkg.tgz`", "flash `image.iso` to a USB drive and boot it", "the deployed environment auto-expires; sign in with the demo account named in the PR comment." |
| `expiry` | Optional. When this evidence stops being usable (a timestamp, or a plain-language duration) — an expired preview is still evidence of what was once true, but a human deciding to exercise it now needs to know it may no longer be reachable. |
| `failure_reason` | Present when `status` is `failed`: plain language for what went wrong. Never present, and never meaningful, for any other status. |

### Non-web previews

`type`, `review_url`, and `instructions` together are what makes a non-web preview a
first-class case rather than an afterthought: a `type: package` or `type: image` entry
carries no `review_url` at all, an `instructions` field a human actually needs (where to
download it, how to install or flash it), and an optional `expiry` the same as any other
kind. Nothing in this schema privileges a URL-shaped preview over an installable one.

### Evidence is tied to an exact commit

`commit` is not optional and is never "the branch" — a task branch moves with every
push, and evidence for an old commit does not describe the new one. This is the same
posture `docs/architecture/verification.md` takes for a verification entry's own
`revision` field: a piece of evidence that does not name the exact commit it was
produced for cannot be trusted to still describe the commit a human is looking at now.
A project's own tooling is expected to produce fresh evidence per commit it is asked to
preview, never to let one build's evidence silently stand in for a later one.

## States

- **`pending`** — the preview is being built; not yet exercisable.
- **`ready`** — the preview is live and exercisable at `review_url`, or reachable by
  `instructions`, for the exact `commit` named.
- **`failed`** — building or deploying the preview did not succeed; `failure_reason`
  says why.
- **`expired`** — the preview was `ready` once but is no longer reachable (`expiry`
  passed, or the project's tooling tore it down). Evidence of what was once true,
  not something a human can exercise now.

Deliberately not the same words `docs/architecture/verification.md` uses for a
verification entry (`pending`/`verified`/`rejected`/`risk-accepted`): the two schemas
describe different facts, and reusing verification's vocabulary here would invite
exactly the confusion §Preview success vs. human verification success exists to
prevent. A `ready` preview and a `verified` human-verification entry are never the same
claim, so they never share a spelling.

## Preview success vs. human verification success

A preview being `ready` says a project's own tooling could build and deploy the exact
commit; a human has not yet looked at it. Human verification (`docs/architecture/verification.md`,
ADR-010 §D4) says a named role *actually exercised* something and recorded a judgment —
`verified`, `rejected`, or `risk-accepted`. These are different facts about different
moments, and one never substitutes for the other:

- A `ready` preview is necessary evidence for a human to be *able* to verify — nobody
  can exercise a preview that failed to build — but it is not sufficient: the software
  running is not the same fact as a human having judged it acceptable (ADR-010 §D4:
  "a passing preview proves the software renders or runs; it cannot prove a human judged
  it acceptable").
- A `failed` or `expired` preview blocks a human from verifying *right now*, but by
  itself it never resolves a required `verification:` entry to `rejected` — a human
  still records that outcome, the same way a human (not the preview) records `verified`.
  A verification entry may cite preview evidence as the `evidence` its record names
  (`docs/architecture/verification.md`'s `evidence` field), but the entry's `state` is
  set by the role who looked, never derived automatically from `status` here.
- Neither schema's states are ever printed as the other's: a `ready` preview is never
  worded as "verified," and a `verified` entry never implies its preview evidence is
  still `ready` at the current head commit — `docs/architecture/verification.md`'s own
  staleness rule (a new commit can invalidate a recorded verification) is the one that
  governs whether an old `verified` entry still speaks for the current commit; this
  contract's own `commit`/`expiry` fields describe only whether the *preview itself* is
  still reachable, a narrower and different question.

A future task that lets a verification entry's `evidence` field point at a structured
preview-evidence object (rather than free text) reads this document's shape; this task
defines the contract and draws the boundary, and commits no other task to that wiring.

## Where a project wires this in

Nothing here needs a template-owned file to change. A project that wants previews adds
its own automation using the extension points `docs/architecture/local-slots.md`
already names:

- **`.github/workflows/ci.yml`'s trailing `steps:` slot** (inside the `<!-- local -->`
  markers at the end of the `checks` job) — for a preview step a project is content to
  run only when the existing `checks` job runs, i.e. never on a draft PR (see next
  section).
- **A project's own separate workflow file** — for a preview trigger that must fire on
  every push to a draft PR, since `ci.yml`'s own `checks` job is deliberately skipped
  while a PR is a draft (`.github/workflows/ci.yml`'s own header comment). A new
  workflow file lives under `.github/` (`CONSTITUTION.md` §3, protected the same way any
  other change there is — planned and reviewed like any other protected-surface change,
  not exempted by being preview-related), with its own `on: pull_request` trigger and no
  draft-skip condition, so it can raise a preview while review is still in progress.

Either way, the project's own tooling is what reads the four input fields above and
produces preview-evidence objects in the shape this document names — t-workflow
supplies no script that calls out to a deployment target, and none is planned by this
task.

## Draft PRs and CI

Nothing in this contract changes when merge-gating CI runs. `.github/workflows/ci.yml`
already skips its `checks` job for a draft PR and runs it once the PR leaves draft
(`ready_for_review`) — that stays exactly as it is. A project that wants a preview to
build on every draft-PR push, ahead of that point, does so through a separate workflow
of its own (previous section) — deliberately, never by default, and never by loosening
the existing skip (`CONSTITUTION.md` §1.5: a guardrail is never weakened to make work
pass, and loosening one is itself a protected change with an ADR).

## Contract checks

`.t-workflow/scripts/check-preview-evidence.sh` validates that a preview-evidence JSON
object (or array of them) matches this document's shape: `commit`, `type`, and `status`
present; `status` one of the four states above; `failure_reason` present when (and only
meaningfully read when) `status` is `failed`. It validates shape only — never a
specific project's choice of `type`, `review_url` scheme, or deployment technology — and
it is wired into no gate (`/t-ship`, `/t-review`, CI): supplying preview evidence is
never required (Non-goals), so nothing here blocks a task that supplies none.

### Example evidence

A web preview, ready:

```json
{
  "commit": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
  "type": "web",
  "status": "ready",
  "review_url": "https://preview.example.com/pr-146/a1b2c3d",
  "expiry": "2026-09-13T00:00:00Z"
}
```

A non-web preview (an installable package), failed:

```json
{
  "commit": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
  "type": "package",
  "status": "failed",
  "instructions": "Would have been installable via `npm install <review_url>`.",
  "failure_reason": "build step 'package' exited 1: missing lockfile entry"
}
```

## Revisit triggers

- A project's real preview practice needs a field this document does not name — resolved
  by amending this document, not by a provider-specific special case a skill has to
  learn.
- A future task wires a `verification:` entry's `evidence` field to a structured
  preview-evidence object rather than free text — it reads this document's shape rather
  than inventing its own (§Preview success vs. human verification success).
- ADR-010 itself is revisited in a way that changes what a "project" party may or may
  never do (ADR-010 §D2) — this document's own boundary follows.
