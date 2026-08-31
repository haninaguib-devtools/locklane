# 442 — Update template to v0.0.9
Issue: #442

## Asked
Sync this repo's template-owned files from t-workflow v0.0.8 to v0.0.9.

## Done when
- `.template-manifest.json` pins `v0.0.9` with `migrations_applied` unchanged (no
  pending migrations at this tag range).
- `AGENTS.md` and `docs/architecture/local-slots.md` match the target tag's content,
  with `AGENTS.md`'s `<!-- local -->` checks-list region keeping this repo's own
  content. `.github/workflows/ci.yml` is unchanged — see Deviations: the template's
  `ci.yml` did not move at this release, so there was nothing to re-graft.
- `.t-workflow/scripts/check-manifest.sh` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Reconciling any drift unrelated to this tag range.
- Upstreaming locklane's own ci.yml steps into the template.

## Decisions made along the way
- None beyond the correction below — this sync needed no re-graft or splice decision
  for `ci.yml` after all.

## Deviations / notes
- **Correction (haninaguib, review pass on PR #443):** the first version of this record
  and the PR body claimed `.github/workflows/ci.yml` had an upstream change this
  release (`timeout-minutes` 20→10) that was manually "re-grafted" over, following the
  precedent set in task #424's v0.0.7 sync. That was wrong. `ci.yml` carries no
  `<!-- local -->` slot (only `CONSTITUTION.md`/`AGENTS.md` have one today), so its
  entry in `.template-manifest.json` hashes locklane's *already-customized* file (the
  manifest-check step, build step, and `timeout-minutes: 20` override added at task
  #424) rather than the pure template's. Comparing that recorded hash against the pure
  v0.0.9 template's hash always shows "changed," regardless of whether the template
  itself moved — which it didn't: `git diff v0.0.8..v0.0.9 -- .github/workflows/ci.yml`
  in `haninaguib-devtools/t-workflow` is empty. `/t-review`'s independent pass caught
  the false narrative; `t-workflow#118` is filed to give `ci.yml` real local slots so
  future syncs stop hitting the same trap. No file content changed as a result of this
  correction — only the record and PR body's account of what happened.
