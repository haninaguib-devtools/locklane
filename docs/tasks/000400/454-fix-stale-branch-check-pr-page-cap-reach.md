# 454 — Fix stale-branch check: PR page cap reached and to_epoch fails on empty timestamps
Issue: #454

## Asked
The daily stale-branches safety net (`scripts/check-stale-branches.sh`, from #39) can no
longer be trusted to mean "clean" when it passes. The repo has passed 200 PRs, so
`gh pr list --state all --limit 200` sits at its own page cap and the script warns its
scan may be incomplete. Separately, `to_epoch` leaks `date: invalid option -- 'j'` to
stderr — at least one PR row's timestamp failed both the GNU and BSD date parse — so
that row was never compared on a real timestamp. Fix both so a clean pass means the
whole PR set was scanned and every row was compared on a real timestamp.

## Done when
- The `gh pr list` call returns the repository's full merged/closed PR set — a raised
  limit comfortably above the current PR count or pagination — and the existing at-cap
  warning still fires if the new bound is ever reached.
- Running `./scripts/check-stale-branches.sh` from the repo root produces no `date:`
  error on stderr; a row whose relevant timestamp is empty or unparseable is handled
  explicitly (skipped with a stated reason, or resolved from the other timestamp field)
  instead of being compared on a garbage value.
- The documented exit-code contract is unchanged: 0 clean, 1 stale found, 2 scan did
  not complete.

## Explicitly not
- No change to `.github/workflows/stale-branches.yml` (schedule, permissions, or
  invocation) — the workflow already just runs the script.
- No behavior change beyond the two fixes: the check stays report-only and never
  deletes anything.

## Decisions made along the way
- none yet

## Deviations / notes
- none yet
