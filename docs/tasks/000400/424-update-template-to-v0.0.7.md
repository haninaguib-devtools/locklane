# 424 — Update template to v0.0.7
Issue: #424

## Asked
Sync this repo's template-owned files from t-workflow v0.0.6 to v0.0.7.

## Done when
- `.template-manifest.json` pins `v0.0.7` with `migrations_applied` unchanged (no
  pending migrations at this tag range).
- The added and changed template files listed on the issue match the target tag's
  content (with `.github/workflows/ci.yml`'s local `manifest` and `build` jobs
  preserved — see Deviations).
- `.t-workflow/scripts/check-manifest.sh` and `.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Reconciling any drift unrelated to this tag range.

## Decisions made along the way
- `.github/workflows/ci.yml` is not a local-slot file (only `CONSTITUTION.md`/
  `AGENTS.md` carry `<!-- local -->` markers), but this repo's copy carries two jobs
  the template's v0.0.7 doesn't: `manifest` (checks the pinned manifest) and `build`
  (`./mvnw -B test`, per `AGENTS.md` §Checks item 1). The human confirmed: sync the
  file from the template, then re-append these two local jobs by hand so CI keeps
  passing, rather than either dropping them or aborting the sync (haninaguib,
  2026-08-30).

## Deviations / notes
- Same as the decision above: `ci.yml` receives a manual local re-addition after the
  mechanical template copy, since the local-slots mechanism doesn't cover this file.
