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
- none

## Deviations / notes
- The task branch was created from `origin/main` rather than a fast-forwarded local
  `main`: local `main` is checked out in the primary worktree, so it cannot be moved
  from this checkout, and it was behind-only (an ancestor of `origin/main`), which is
  the case the fast-forward rule treats as safe.
