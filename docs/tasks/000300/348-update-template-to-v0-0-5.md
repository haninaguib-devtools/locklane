# 348 — Update template to v0.0.5
Issue: #348

## Asked
Sync this repo's template-owned files from the pinned t-workflow release v0.0.3
forward to v0.0.5, via /t-update: copy in the target tag's versions of changed and
added owned files, preserve this repo's `<!-- local -->` slot content, apply pending
migrations, and rewrite `.template-manifest.json` to pin v0.0.5.

## Done when
- All added/changed template-owned files match their v0.0.5 versions, with
  `<!-- local -->` slot content preserved from this repo (`AGENTS.md`).
- Added files present: `.t-workflow/scripts/review-snapshot.sh`,
  `docs/adr/006-single-task-driving.md`.
- `.template-manifest.json` pins v0.0.5 with fresh normalized hashes;
  `migrations_applied` stays 0 (no pending migrations in the v0.0.3→v0.0.5 range).
- `.t-workflow/scripts/check-manifest.sh` and
  `.t-workflow/scripts/consistency-check.sh` pass.
- Draft PR opened; /t-review required before shipping (template-owned files are
  protected surfaces).

## Explicitly not
- No changes to any file outside the template-owned set plus this record, except the
  local ADR renumber below (human-directed, resolving the collision this sync
  created).
- No local edits to the incoming template content — a wanted change is upstreamed to
  the template, never patched here.

## Decisions made along the way
- Target tag v0.0.5 = the template's latest; human confirmed the sync gate
  (haninaguib, 2026-08-29).
- The template's new `docs/adr/006-single-task-driving.md` collided in number with
  this repo's local ADR-006 (automatic worktree cleanup sweep); resolved by
  renumbering the local ADR to 008 — file renamed to
  `docs/adr/008-automatic-worktree-cleanup-sweep.md`, references updated in
  `CONSTITUTION.md` §4 (local slot), `engine/src/main/resources/application.yml`
  (comment), and the task-237 record's file pointer (haninaguib, 2026-08-29).

## Deviations / notes
- Update stopped after applying the sync, before push/PR: `consistency-check.sh`
  fails because the template's new `docs/adr/006-single-task-driving.md` collides in
  number with this repo's pre-existing local `docs/adr/006-automatic-worktree-cleanup-sweep.md`
  — the checker resolves the new t-drive skill's "ADR-006 D<n>" citations to the
  local file and finds no matching decision headings. Resolved by the human-directed
  renumber recorded under Decisions; `consistency-check.sh` passes again.
- The systemic collision risk remains: local ADR-002 (rewrite stack) and ADR-007
  (multi-user tenancy) still sit in the template's future numbering path — a
  numbering convention (e.g. local ADRs from 100) is worth a follow-up issue.
- Plan gate also unsatisfied: the diff touches protected surfaces and issue #348 has
  no `## Plan` yet — `/t-plan 348` required before the draft PR can pass CI.
