# 911 — Fix Sonar triage OIDC failure with own GitHub token
Issue: #911

## Asked
The nightly `Sonar triage` workflow fails at its triage step with "Could not fetch an OIDC token" because `anthropics/claude-code-action` falls back to an OIDC exchange against the Claude GitHub App when no `github_token` input is given. Pass the job's own token so the exchange is skipped, following the thyme-clinic final state: the agent stays bounded by the job's `contents: read` instead of the app token's wider default scope, and the workflow becomes testable from a branch.

## Done when
- `grep -n 'github_token' .github/workflows/sonar-triage.yml` shows a `github_token` input on the Claude step using the job's own token.
- A manual dispatch of `Sonar triage` (`gh workflow run "Sonar triage" --ref <branch>`) finishes green with the triage table in the job summary and a `sonar-triage-table` artifact (or a "no open issues" note when the backlog is empty).
- `scripts/check.sh` passes (or is skipped as docs-only, if applicable).

## Explicitly not
- Making Sonar or the triage report gate merges or builds.
- Changing `.github/workflows/sonar.yml`, `.github/scripts/sonar-triage-fetch.sh`, or `docs/notes/sonar-triage.md`.
- Granting `id-token: write`; passing the job's own token removes the need for it.

## Decisions made along the way
- Used `github_token: ${{ secrets.GITHUB_TOKEN }}` verbatim from the thyme-clinic
  end state (including its comment) rather than the repo's usual
  `${{ github.token }}` spelling, so the two workflows stay directly comparable.
- `scripts/check.sh` FAILS locally on engine tests that assert a clean host git/gh
  environment (`ProjectCheckoutServiceTest`, credential-helper expectations), and
  the failure count changes with `GH_TOKEN` set vs unset (4 vs 2). The diff here
  touches only `.github/workflows/sonar-triage.yml`, which no Java test reads;
  CI `build` on the trunk (`a505cef`) is green, so this is host-environment
  leakage, not a regression. Reported as-is; the check was not weakened.

## Deviations / notes
- Live manual dispatch of `Sonar triage` on the work branch cannot be verified
  from here; left as the human check at the ship gate, same as #909 did.
- Fix pass on the review's High finding (local `scripts/check.sh` FAIL): no code
  change — none can address it. The diff touches only workflow YAML plus this
  record, which no Java test reads; the same failures occur with `GH_TOKEN`
  unset; and CI on this exact head (`863d676`) reports `build` pass plus `scan`
  pass, so the local failure is host-environment leakage (host `GH_TOKEN` and
  git/gh state), pre-existing and unrelated to this task.

## Agents
- plan: opencode / meta/muse-spark-1.3-contributor
- work: opencode / meta/muse-spark-1.3-contributor
- work (fix): opencode / meta/muse-spark-1.3-contributor
