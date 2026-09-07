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

## Decisions made along the way
- none

## Deviations / notes
- none
