# 751 — Update template to v0.1.1
Issue: #751

## Asked
Bring this repo's template-owned files up to date with the upstream t-workflow
template, moving from the pinned `v0.0.17` to `v0.1.1`.

## Done when
- `.template-manifest.json` records `v0.1.1` as the pinned tag, with a fresh hash for
  every currently template-owned file and `migrations_applied` unchanged at 3 (no
  migration in this range applies).
- Every added/changed file in `v0.1.1`'s manifest scope is copied in verbatim, except
  the four local-slot files (`AGENTS.md`, `CONSTITUTION.md`, `.github/workflows/ci.yml`,
  `.gitignore`), whose `<!-- local -->` regions keep this repo's own current content.
- `./.t-workflow/scripts/consistency-check.sh` and `./.t-workflow/scripts/check-manifest.sh`
  both pass against the synced tree.

## Explicitly not
- Adopting the new `t-config` skill's behavior or any other new-feature content beyond
  copying the file in — using it is a separate decision, not part of this sync.
- Filling in the new `AGENTS.md` "Reviewer model" local slot with anything other than
  the template's neutral default — this repo has no reviewer-model preference yet.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- none

## Deviations / notes
- Issue #751 was opened and this branch worked without first going through `/t-plan`,
  even though the sync touches nearly every protected surface in the repo
  (`CONSTITUTION.md` §3) — a `/t-plan` pass is required first for exactly that case,
  and `/t-work`'s own Phase 1 gate should have caught the omission and did not. A cold
  review caught it instead; `/t-plan 751` then wrote the `## Plan` section
  retroactively, covering the diff that already existed, and a scoped re-review
  confirmed the gap was closed with no other findings.
- This record was originally written from `docs/tasks/TEMPLATE.md` as it stood before
  this sync applied — the pre-sync template had no `## Origin`/`## Verification`/
  `## Feedback` sections, and `/t-update`'s own procedure creates the record (step 6)
  before copying in the new template shape (step 7). `v0.1.1`'s own `TEMPLATE.md`
  adds those three sections and CI's `check-record.sh` now requires them on every task
  record, including this one — this pass adds them (all `none`: no external origin, no
  named-role verification on the plan, no feedback pass) to satisfy that new check.
