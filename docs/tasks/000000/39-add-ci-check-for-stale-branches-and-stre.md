# 39 — Add CI check for stale branches, and strengthen t-ship/t-fix/t-cancel branch-deletion wording
Issue: #39

## Asked
A merged or closed PR's head branch (`wip/<id>-*` or `fix/<slug>`) should be deleted on
`origin` as part of `/t-ship`, `/t-fix`, or `/t-cancel`, since all three rely on
`forge:pr-merge`/`forge:pr-close` deleting it. That deletion depends on the exact `gh`
command used actually including `--delete-branch` — if a skill invocation (or a manual
merge) omits it, the branch silently lingers on GitHub with nothing surfacing the miss
until someone notices the branch list by eye. Shipping task #36 left
`origin/wip/36-...` behind this way.

## Done when
- A scheduled GitHub Actions workflow (e.g. hourly) lists merged and closed PRs, checks
  whether their head branch (matching `wip/*` or `fix/*`) still exists on `origin`, and
  fails/reports when one does — excluding branches merged/closed within some short grace
  period (e.g. the last hour) to avoid false positives on freshly-merged PRs.
- The check does not delete anything itself — it only reports/fails.
- Manually verified: intentionally leaving a merged PR's branch undeleted causes the next
  scheduled run to report it. (Post-merge — see Explicitly not.)
- `.claude/skills/t-ship/SKILL.md`, `t-fix/SKILL.md`, and `t-cancel/SKILL.md`'s
  branch-deletion steps are reworded to instruct re-reading `docs/adapters/FORGE.md`'s
  `forge:pr-merge`/`forge:pr-close` row immediately before composing the command, and to
  verify the resulting command string includes the active backend's branch-deletion flag
  before running it.

## Explicitly not
- No change to the `forge:pr-merge`/`forge:pr-close` mappings themselves in
  `docs/adapters/FORGE.md` — those are already correct.
- No automatic deletion of stale branches by CI.
- The end-to-end "leave a branch undeleted, watch the scheduled run catch it" check is a
  post-merge human check (`/t-plan`'s human_checks) — GitHub Actions schedules only fire
  off what's on `main`, so this can't be exercised during implementation.

## Decisions made along the way
- Stale-branch detection logic lives in `scripts/check-stale-branches.sh`, called from a
  new `.github/workflows/stale-branches.yml`, matching the existing pattern where
  `ci.yml` calls into `scripts/consistency-check.sh` rather than inlining logic in YAML
  (Claude, 2026-08-25).

## Deviations / notes
- `/t-review 39` (subagent, PR #40 review, 2026-08-25) found a high-severity bug: the
  original `scripts/check-stale-branches.sh` never checked whether its `gh pr list` or
  `gh api .../branches/<branch>` calls actually succeeded, so a `gh` failure (bad
  token, rate limit, outage) reported the same "OK, nothing stale" success line and
  exit 0 as a genuinely clean scan — exactly the kind of silent miss the check exists
  to catch. Fixed in fix mode (Claude, 2026-08-25): both calls' exit status is now
  checked; either failure prints `FAIL: ...` and exits 2, a distinct code from clean
  (0) and stale-found (1). Verified by reproducing the reviewer's exact repro
  (`GH_TOKEN=invalid_bad_token_xyz`) and confirming it now fails loudly with exit 2,
  then re-running with a valid token to confirm the real check still finds the same 3
  stale branches as before.
- The same review also raised a medium finding (a PR listing truncated at the 200-row
  page limit still reports "OK" rather than "incomplete") — not addressed here per
  Fix mode scope (only the named high finding); noted for the human to decide whether
  to fold into a follow-up.
