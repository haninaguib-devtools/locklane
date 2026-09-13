# 909 — Run Sonar scan on PRs and main, add nightly triage report
Issue: #909

## Asked
Locklane's `Sonar` workflow runs only when a maintainer starts it by hand, and
nothing triages the Sonar backlog. Following the thyme-clinic example: run the
scan automatically on pull requests and pushes to `main` (keeping manual
dispatch), read the Sonar host from the `SONAR_HOST` repository variable instead
of the secret (secret values get masked everywhere they appear in displayed
output, which breaks links), and add a nightly `Sonar triage` workflow that
fetches open Sonar issues and has an agent recommend fix, accept, or false
positive for each one, publishing the table to the run's job summary plus an
artifact, with a short doc describing the morning routine. Both workflows stay
informational: they report but never gate. `CLAUDE_CODE_OAUTH_TOKEN` already
exists as a repository secret, as does `vars.SONAR_HOST`.

## Done when
- `grep -E 'pull_request|workflow_dispatch' .github/workflows/sonar.yml` shows
  `pull_request`, `push: branches: [main]`, and `workflow_dispatch` triggers,
  and `SONAR_HOST_URL` reads `${{ vars.SONAR_HOST }}`.
- `.github/workflows/sonar-triage.yml` exists with a nightly `schedule` plus
  `workflow_dispatch`; `test -x .github/scripts/sonar-triage-fetch.sh` passes
  and the script outputs `{sonarHost, projectKey, issues}` JSON for project
  `locklane`, paging past 100 issues and excluding anything tagged `tracked`.
- A manual dispatch of `Sonar triage` finishes green with the triage table in
  the job summary and a `sonar-triage-table` artifact (or a "no open issues"
  note when the backlog is empty); a missing table with a non-empty backlog
  fails the job loudly instead of reporting all-clear.
- The scan has no `sonar.qualitygate.wait` and its job is not a required status
  check; `scripts/check.sh` passes (or is skipped as docs-only, if applicable).

## Explicitly not
- Making Sonar gate merges or builds; `build.yml` and its build-inputs skip
  logic are untouched.
- TypeScript coverage in the scan — that is #908; until it lands, the scan and
  triage cover what Sonar already analyses.
- Deleting the legacy `SONAR_HOST` secret; that follows once a
  variable-based run is verified.

## Decisions made along the way
- `sonar.yml` keeps its existing steps/pins and gains `pull_request` + `push:
  branches: [main]` alongside `workflow_dispatch`; `SONAR_HOST_URL` moves from
  `secrets.SONAR_HOST` to `vars.SONAR_HOST`, `SONAR_TOKEN` stays a secret, and no
  `sonar.qualitygate.wait` is added so the scan stays informational.
- The fetch script filters the `tracked` tag client-side with `jq` (the Sonar
  `tags` search parameter semantics vary) and loops `p=1..` until the fetched
  count reaches `total`, so backlogs over one page are fully covered; verified
  with a stubbed `curl` (250 issues over 3 pages → 225 untracked) plus empty and
  missing-credential cases.
- The triage workflow pins `anthropics/claude-code-action` and
  `actions/upload-artifact` by SHA like the repo's other workflows, passes
  `OPEN_COUNT` via `GITHUB_ENV`, writes "No open Sonar issues" on an empty
  backlog, and exits 1 when the table is missing with a non-empty backlog;
  publish-step logic verified locally for all three cases.
- PR-triggered scans run with secrets only on same-repo PRs; fork PRs lack
  `SONAR_TOKEN` and will fail the scan step — left as-is per the issue (the scan
  stays informational and is not a required check).

## Deviations / notes
- Manual dispatch of `Sonar triage` (green run + summary table + artifact) cannot
  be verified from here; left as the human check on the plan.
- `docs/notes/` is new in this diff (no `docs/notes/` existed before).

## Agents
- plan: opencode / meta/muse-spark-1.3-contributor
- work: opencode / meta/muse-spark-1.3-contributor
