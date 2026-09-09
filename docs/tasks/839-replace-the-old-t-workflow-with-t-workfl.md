# 839 — Replace the old t-workflow with t-workflow v0.0.5
Issue: #839

## Asked
Remove the old template-based t-workflow (every file its manifest owns, its migrations, and its CI) and install t-workflow v0.0.5 in its place, carrying the old local slots into `.t-workflow/config` and `AGENTS.md`.

## Done when
- `.t-workflow/VERSION` reads `v0.0.5` and every path in the installer's owned set matches the release.
- `.t-workflow/scripts/ci.sh` passes on this PR.

## Explicitly not
- Changing anything else in the repository.

## Decisions made along the way
- none

## Deviations / notes
- none
