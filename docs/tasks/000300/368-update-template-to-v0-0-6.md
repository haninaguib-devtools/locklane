# 368 — Update template to v0.0.6
Issue: #368

## Asked
Sync this repo's template-owned files from `haninaguib-devtools/t-workflow` v0.0.5 to
v0.0.6, so the repo stays current with the delivery-pipeline template.

## Done when
- `.template-manifest.json` records tag `v0.0.6` with a fresh hash for every
  template-owned file.
- `.github/workflows/ci.yml` carries the target tag's `edited`-event trigger guard
  while keeping this repo's own `manifest` and `build` (`mvnw test`) jobs.
- `.t-workflow/scripts/check-manifest.sh` and
  `./.t-workflow/scripts/consistency-check.sh` pass against the synced tree.

## Explicitly not
- No migrations apply — none pending beyond `migrations_applied: 0`.

## Decisions made along the way
- The incoming `ci.yml` does not carry this repo's `manifest` and `build` CI jobs
  (consumer-specific, no local-slot marker). Restoring them after the raw copy was
  confirmed with the human up front (same fix as task #348 / commit 9f07d84) rather than
  accepting the regression or expanding `docs/architecture/local-slots.md` mid-sync.

## Deviations / notes
- none
