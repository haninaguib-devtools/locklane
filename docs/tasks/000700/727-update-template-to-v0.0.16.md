# 727 — Update template to v0.0.16
Issue: #727

## Asked
Sync this repo's template-owned files forward from the pinned t-workflow v0.0.15 to
v0.0.16, applying any pending migrations, without disturbing this repo's own local
customizations.

## Done when
- `.template-manifest.json` records tag `v0.0.16` and `migrations_applied: 3`.
- Every template-owned file matches its v0.0.16 content, except that each
  `<!-- local -->` slot still holds this repo's own content.
- Migration `V3__protected-paths-local-slots.md` is applied: `CONSTITUTION.md` §3 and
  `.t-workflow/scripts/protected-paths.sh`'s `patterns` array each gain a
  `<!-- local -->` slot (empty placeholder — this repo had no unmarked customization
  in either to relocate).
- `.t-workflow/scripts/check-manifest.sh` and `./.t-workflow/scripts/consistency-check.sh` both pass.

## Explicitly not
- No change to application code (engine/client) — this is a template-file sync only.

## Decisions made along the way
- none

## Deviations / notes
- none
