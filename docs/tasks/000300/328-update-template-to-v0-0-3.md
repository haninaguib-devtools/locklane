# 328 — Update template to v0.0.3
Issue: #328

## Asked
Sync this repo's template-owned files forward from the pinned t-workflow release
`v0.0.2` to the latest release, `v0.0.3`, so the repo stays current with the delivery
pipeline's skills, adapters, and scripts.

## Done when
- `.template-manifest.json` records `v0.0.3` as the pinned tag.
- Every template-owned file matches `v0.0.3`'s content (splicing this repo's own
  `<!-- local -->` regions in `CONSTITUTION.md` and `AGENTS.md` back in unchanged).
- `.t-workflow/scripts/check-manifest.sh` passes against the freshly written manifest.
- `./.t-workflow/scripts/consistency-check.sh` passes.
- CI keeps working: the consumer-specific `manifest` job, the `mvnw -B test` build job,
  and the `target/` `.gitignore` entry (none of which are template content) survive the
  sync.

## Explicitly not
- No migrations applied — the target tag carries no `migrations/` directory, so none
  are pending.
- No changes beyond template-owned files and the reapplied CI/gitignore consumer
  additions.

## Decisions made along the way
- `.github/workflows/ci.yml` and `.gitignore` are template-owned but carry no
  `<!-- local -->` markers, so a literal copy-in from `v0.0.3` would delete this repo's
  `manifest` CI job, its `mvnw -B test` build job, and its `target/` ignore entry —
  none of which the template can provide for itself (`docs/architecture/manifest.md`
  §The CI lock: "a consumer wires this into its own CI"). Flagged at the sync gate and
  confirmed by the human: copy the template's version in as the skill specifies, then
  reapply those three additions on top, in this same task (hani, 2026-08-29).

## Deviations / notes
- none
