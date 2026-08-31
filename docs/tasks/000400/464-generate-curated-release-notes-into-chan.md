# 464 — Generate curated release notes into CHANGELOG.md and the release body
Issue: #464 · Part of: #462

## Asked
A release's notes tell a human what changed, grouped by kind, instead of the boilerplate
"Permanent release of version X". At release cut, the changes since the previous release
tag are collected from `main`'s squash commits — every subject is `[<id>] <title>
(#<pr>)`, so each maps to its tracker issue and PR — and grouped under headings derived
from each issue's classification label (`enhancement` → Features, `bug` → Fixes,
`documentation` → Documentation, `question`/other → Other). The result lands in two
places: appended as a new versioned section of a committed root `CHANGELOG.md`, and as
the body of the `vX.Y.Z` GitHub Release. The plan pins the sequencing: the notes PR is
merged through the pipeline *before* the dispatch, and `release.yml` extracts (never
regenerates) the derived version's section as the release body, failing loudly when the
section is missing.

## Done when
- `CHANGELOG.md` exists at the repo root with one section per release from this point
  forward, each grouped by label-derived headings, each entry carrying its issue/PR
  references.
- Cutting a release produces a GitHub Release whose body matches that version's
  `CHANGELOG.md` section.
- The notes-generation step is scripted and reproducible (same inputs → same notes),
  not hand-typed into the workflow dispatch.
- `docs/architecture/releasing.md` no longer lists changelog generation as a non-goal
  and describes where notes come from.
- A human judges the generated notes for one real release to be accurate and readable.

## Explicitly not
- No backfilling of notes for releases published before this lands — the changelog
  starts at the first release cut with it.
- No hand-curated prose beyond what grouping and titles give; an editorial summary, if
  ever wanted, is written by the human into the notes PR like any other change.
- Nothing here bumps `<revision>` or dispatches releases automatically — the human
  still decides when a version is cut (releasing.md keeps that non-goal).
- `.github/workflows/ci.yml` untouched — it is hashed in the pinned template manifest.

## Decisions made along the way
- Sequencing pinned at /t-plan (agent, 2026-08-31, from the issue's own framing per the
  driving session's delegation): notes PR merged before dispatch; the workflow only
  extracts the committed section, so the release body equals the changelog section by
  construction, `main` never moves by a workflow-opened PR (CONSTITUTION §1.2), and
  `release.yml` keeps `contents: write` only.
- Generation and extraction live in one script, `scripts/generate-release-notes.sh`
  (`generate` and `extract` modes), so the two cannot drift.
- Section heading format `## v<X.Y.Z> — <YYYY-MM-DD>`: the date is an explicit input
  (`--date`, defaulting to today) so "same inputs → same notes" holds literally.
- A squash subject that does not parse as `[<id>] <title> (#<pr>)` is listed under
  Other verbatim, never dropped silently; an issue whose labels carry none of the four
  classification labels also groups under Other.

## Deviations / notes
- Blocker gate: `check-blocker-gate.sh` reports the sole blocker #463 as OPEN. Discharged
  by the driving session for this driven run (/t-drive Phase 2 step 1: sibling blockers
  are governed by /t-drive, and #463's outcome is "merged" — its work is commit 5dc3b4f
  on this branch's base `wip/462-integration`); in a driven run a child issue closes only
  when the initiative's aggregate PR reaches `main`. No other blockers exist.
- Branch deliberately based on `wip/462-integration` (tip a3795d9), not `main`, per
  /t-drive (ADR-004 Decision 1); not rebased onto `origin/main`, and diff-vs-scope runs
  against `origin/wip/462-integration`. Draft PR opens with base `wip/462-integration`
  and carries no auto-close phrase — the aggregate PR closes #464.
