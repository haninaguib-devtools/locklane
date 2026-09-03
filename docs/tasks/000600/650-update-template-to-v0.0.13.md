# 650 — Update template to v0.0.13
Issue: #650

## Asked
Sync this repo's template-owned files forward from t-workflow tag `0.0.11` to `v0.0.13`
with `/t-update`, as one ordinary task. Two template changes land in that range:
t-workflow#122 (the template-owned-paths script stops flagging consumer files added
under a protected directory as template-owned — the false positive this repo hit) and
t-workflow#124 (skills and scripts resolve the actual trunk branch through a new
`.t-workflow/scripts/trunk-ref.sh` instead of hardcoding `main`; this repo's trunk is
`main`, so behaviour is unchanged here).

Files: 1 added (`.t-workflow/scripts/trunk-ref.sh`), 13 changed, 40 unchanged, 0
removed from the owned list. Migrations applied: none (V1 was already applied; no
higher migration exists at `v0.0.13`).

## Done when
- `.template-manifest.json` pins `v0.0.13` with every owned file's normalized hash;
  `migrations_applied` stays 1.
- `.t-workflow/scripts/check-manifest.sh` passes against the synced tree.
- `AGENTS.md` local slots (the `/l-release` row and the Checks section) are
  byte-identical to before the sync.
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No changes to non-template files, application code, or local-slot content.

## Decisions made along the way
- Files that only *mention* the local-slot marker text (`.claude/skills/t-update/SKILL.md`,
  `.t-workflow/scripts/plumbing-test.sh`, `docs/architecture/manifest.md`) are copied
  from the tag verbatim, not spliced: the splice rule applies to the slot files
  `docs/architecture/local-slots.md` names, not to any file containing the literal
  marker. A first pass had spliced them; it was corrected before the manifest was
  written and every non-slot file verified byte-identical to `v0.0.13` (agent, 2026-09-03).

## Deviations / notes
- The task branch was created from `origin/main` rather than a fast-forwarded local
  `main`: local `main` is checked out in the primary worktree, so it cannot be moved
  from this checkout, and it was behind-only (an ancestor of `origin/main`), which is
  the case the fast-forward rule treats as safe.
- Sequencing: `/t-update` applied and committed the sync (step 7–9 of its procedure)
  before the issue carried a `## Plan`; its Phase 3 plan gate then stopped it short of
  the draft PR. `/t-plan 650` added the plan afterwards, and `/t-work 650` resumed on
  the existing branch to run the plan's checks and open the PR. No file changed
  between the plan and the PR beyond this record.
