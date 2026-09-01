# 478 — Update template to 0.0.11
Issue: #478

## Asked
Sync this repo's template-owned files from t-workflow v0.0.9 to 0.0.11 (spanning
v0.0.10), applying the one pending migration.

## Done when
- `.template-manifest.json` pins `0.0.11` with `migrations_applied` set to `1`.
- `.github/workflows/ci.yml` carries the new `<!-- local -->` slots, with this repo's
  pre-sync `timeout-minutes: 20` and its trailing `check-manifest.sh` +
  `./mvnw -B test` steps relocated inside them, unchanged.
- `AGENTS.md` and `CONSTITUTION.md` match the target tag's content outside their
  `<!-- local -->` regions, with this repo's own content preserved inside them
  (AGENTS.md gains a new, currently-empty local-skill-row slot after the `t-*` table).
- `docs/architecture/local-slots.md`, `.t-workflow/scripts/check-manifest.sh`,
  `.t-workflow/scripts/consistency-check.sh`, `.t-workflow/scripts/plumbing-test.sh`,
  and `.claude/skills/t-update/SKILL.md` match the target tag's content verbatim (no
  local-slot markers in any of these).
- `.t-workflow/scripts/check-manifest.sh` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Adding a row to AGENTS.md's new local-skill slot — no consumer-local skill exists in
  this repo yet; the slot lands empty (its neutral placeholder). Unblocks issue #477 at
  a future `/t-plan`, but re-planning #477 is not this task.
- Reconciling any drift unrelated to this tag range.

## Decisions made along the way
- Target resolved to tag `0.0.11` rather than `v0.0.10`: `0.0.11` is the template
  repo's chronologically newest tag and a direct git descendant of `v0.0.10`, just
  named without the `v` prefix every prior tag used (an apparent upstream naming
  slip). Treated as the real latest release rather than skipped.
- Verified each of the 7 flagged-changed files against the template's own history
  (`git diff v0.0.9..0.0.11 -- <path>` in a clone of `haninaguib-devtools/t-workflow`),
  not just a diff against this repo's customized tree — per the false-positive lesson
  from task #442's v0.0.9 sync. All 7 are real upstream deltas this time.

## Deviations / notes
- Applied migration V1 ("ci.yml gains local slots"): read this repo's pre-sync
  `.github/workflows/ci.yml` via `git show HEAD:…` (nothing was committed yet on this
  branch), diffed it against the old template's v0.0.9 `ci.yml`, and found two
  customizations — `timeout-minutes: 20` (template default is 10) and three trailing
  steps (a manifest-check step, `actions/setup-java`, and `./mvnw -B test`). Wrote the
  target's `ci.yml` (with its two new `<!-- local -->` slots), then relocated both
  customizations, unchanged, into the new slots. Confirmed nothing landed outside the
  markers: `check-manifest.sh --hash-file` on the spliced file matches the same
  script's hash on a copy with both slots stripped to empty.
