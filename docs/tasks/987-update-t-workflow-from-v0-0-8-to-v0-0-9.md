# 987 — Update t-workflow from v0.0.8 to v0.0.9
Issue: #987

## Asked
Move t-workflow from v0.0.8 to v0.0.9 by replacing every t-workflow-owned file with the release's copy. Consumer-owned files (`AGENTS.md`, `.t-workflow/config`) are untouched.

## Done when
- `.t-workflow/VERSION` reads `v0.0.9` and every path in the installer's owned set matches the release.
- `.t-workflow/scripts/ci.sh` passes on this PR.

## Explicitly not
- Changing anything else in the repository.

## Decisions made along the way
- none

## Deviations / notes
- none

## Agents
