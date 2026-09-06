# 731 — Update template to v0.0.17
Issue: #731

## Asked
Sync this repo's template-owned files forward from the pinned v0.0.16 to v0.0.17,
so the workflow skills and docs stay current with the upstream template.

## Done when
- `.template-manifest.json` pins v0.0.17 with hashes matching the freshly synced files.
- The 5 changed files (`.claude/skills/t-plan/SKILL.md`, `.claude/skills/t-review/SKILL.md`,
  `AGENTS.md`, `docs/architecture/local-slots.md`, `docs/workflow.md`) carry the target
  tag's content, with `AGENTS.md`'s `<!-- local -->` region (the `/l-release` row and
  local checks list) preserved from this repo's current content.
- `.t-workflow/scripts/check-manifest.sh` and `.t-workflow/scripts/consistency-check.sh` pass.
- Draft PR opened.

## Explicitly not
- No migrations to apply — template is still at V3, and this repo's manifest already
  records `migrations_applied: 3`.
- No files added or removed — the template-owned path list is unchanged between the
  two tags.

## Decisions made along the way
- none

## Deviations / notes
- none
