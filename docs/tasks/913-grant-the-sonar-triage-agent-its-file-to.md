# 913 — Grant the Sonar triage agent its file tools like thyme-clinic
Issue: #913

## Asked
The `Sonar triage` workflow's Claude step spins until the job timeout without producing a table, because in agent mode (`prompt` input) `anthropics/claude-code-action` grants no tools by default: every Read/Write the agent attempts is denied. Grant the file tools explicitly and cap turns, exactly like the thyme-clinic end state, so the step triages and writes `sonar-triage-table.md` instead of burning clock.

## Done when
- `grep -n 'allowedTools' .github/workflows/sonar-triage.yml` shows a `claude_args` input on the Claude step granting `Read,Write,Grep,Glob,LS` with `--max-turns 60` (thyme-clinic wording).
- A manual dispatch of `Sonar triage` finishes green well inside the job timeout with the triage table in the job summary and a `sonar-triage-table` artifact (or a "no open issues" note when the backlog is empty).
- `scripts/check.sh` passes (or is skipped as docs-only, if applicable).

## Explicitly not
- Changing the triage prompt shape, the publish/upload steps, or the job timeout/schedule.
- Changing `.github/workflows/sonar.yml`, `.github/scripts/sonar-triage-fetch.sh`, or `docs/notes/sonar-triage.md`.
- Making Sonar or the triage report gate merges or builds.

## Decisions made along the way
- Copied thyme-clinic's `claude_args` block verbatim except the last comment
  line: thyme-clinic says a toolless step "succeeds having produced nothing",
  but the locklane run on pinned `v1.0.99` spun to the job timeout instead, so
  the comment records the observed behavior.
- `scripts/check.sh` FAILS locally with the same known host-environment
  signature as #911 (`ProjectCheckoutServiceTest` credential-helper assertions
  plus the flaky `ProjectAgentSessionWebSocketIntegrationTest` timing test).
  The diff touches only `.github/workflows/sonar-triage.yml`, which no Java
  test reads; CI `build` on the trunk is green. Reported as-is; the check was
  not weakened.

## Deviations / notes
- Live manual dispatch on the work branch cannot be verified from here; left as
  the human check at the ship gate, same as #909/#911.

## Agents
- plan: opencode / meta/muse-spark-1.3-contributor
- work: opencode / meta/muse-spark-1.3-contributor
