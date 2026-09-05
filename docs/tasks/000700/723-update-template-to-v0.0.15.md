# 723 — Update template to v0.0.15
Issue: #723

## Asked
Sync this repo's template-owned files forward from t-workflow v0.0.14 to v0.0.15.

## Done when
- The manifest is pinned to v0.0.15 with migrations_applied unchanged (no new migration exists between v0.0.14 and v0.0.15).
- The 5 changed template files are synced: `.claude/skills/t-update/SKILL.md`, `.github/workflows/review-gate.yml`, `.gitignore`, `.t-workflow/scripts/plumbing-test.sh`, `docs/architecture/local-slots.md`. 51 other template-owned files are unchanged at this tag; none were added or removed.
- `.t-workflow/scripts/check-manifest.sh` and `./.t-workflow/scripts/consistency-check.sh` both pass against the synced tree.

## Explicitly not
- No migration to apply: the template still only defines V1/V2, and this repo already has both applied (migrations_applied: 2).
- Neither `.gitignore` nor `.github/workflows/review-gate.yml` currently carries a `<!-- local -->` marker in this repo, and neither's live content (empty ignore-additions region, `timeout-minutes: 10`) differs from the template's own default, so both are copied in directly rather than spliced.

## Decisions made along the way
- The template's v0.0.15 `local-slots.md` and `t-update/SKILL.md` document two *new* local slots (`.gitignore`'s trailing region, `review-gate.yml`'s `timeout-minutes`), but no migration file promotes an existing consumer's copy of either from unmarked to marked — because for a consumer whose current content already matches the template's own default in that spot (true here: an empty `.gitignore` tail, `timeout-minutes: 10`), a plain copy-in and a splice produce an identical result. Verified both conditions hold in this repo before treating the plain-copy path as safe rather than manufacturing a migration that isn't defined upstream.

## Deviations / notes
- None.
