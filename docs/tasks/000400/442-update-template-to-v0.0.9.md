# 442 — Update template to v0.0.9
Issue: #442

## Asked
Sync this repo's template-owned files from t-workflow v0.0.8 to v0.0.9.

## Done when
- `.template-manifest.json` pins `v0.0.9` with `migrations_applied` unchanged (no
  pending migrations at this tag range).
- The 3 changed template files match the target tag's content, with two local
  carve-outs (see Deviations): `AGENTS.md`'s `<!-- local -->` checks-list region keeps
  this repo's own content, and `.github/workflows/ci.yml`'s local manifest-check and
  build steps, plus its `timeout-minutes` override, are re-grafted.
- `.t-workflow/scripts/check-manifest.sh` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Reconciling any drift unrelated to this tag range.
- Upstreaming locklane's own ci.yml steps into the template.

## Decisions made along the way
- `.github/workflows/ci.yml` is still not a local-slot file (only `CONSTITUTION.md`/
  `AGENTS.md` carry `<!-- local -->` markers today), but this repo's copy carries local
  additions the template doesn't: a `Template-owned files match the pinned manifest`
  step, a `Build and test every module` step (Java setup + `./mvnw -B test`), and a
  `timeout-minutes: 20` override justified by that build step. Same precedent as task
  #424's v0.0.7 sync: take the target tag's file whole, then re-append the local steps
  and keep the timeout override, since the reason for both (a real build step in this
  job) is unchanged by this release.

## Deviations / notes
- Same as the decision above: `ci.yml` receives a manual local re-addition after the
  mechanical template copy, since the local-slots mechanism doesn't cover this file.
