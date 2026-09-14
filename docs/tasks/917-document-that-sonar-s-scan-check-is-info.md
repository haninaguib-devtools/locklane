# 917 — Document that Sonar's scan check is informational and never gates /t-ship
Issue: #917

## Asked

`/t-ship` step 2 runs `gh pr checks <pr> --watch`, which blocks until every check in
the PR's rollup finishes. This project has a `scan` check
(Sonar, `.github/workflows/sonar.yml`) whose own header comment says it is
informational only — it never fails the build on a red quality gate (no
`sonar.qualitygate.wait`), and its job name is not among the required status-check
contexts `.t-workflow/scripts/github-bootstrap.sh` sets — but nothing tells
`/t-ship` that, so an agent running it ends up sitting and waiting on `scan` before
it can merge, even though `scan` never blocks the merge itself.

`scan` is a project addition, not a t-workflow check, so the fix belongs in this
project's own root `AGENTS.md` (not `.t-workflow/AGENTS.md`, which `/t-update`
replaces). Add a "Checks" note there explaining that `scan` is informational and
telling `/t-ship` step 2 to wait for every other check to finish, excluding `scan`,
before merging — naming `scan` as the one exclusion, rather than naming the checks to
wait for, so a future check this repo's CI adds is gated by default and has to be
explicitly opted out, rather than silently skipped for not being on an allow-list.

thyme-clinic already carries the equivalent note (its issue #50 / PR #51,
`thyme-clinic/thyme-clinic@e7f21c2`) — this task does the same thing here, worded for
locklane's own `AGENTS.md` structure (its "## Project notes" section, not a numbered
`## Constraints` item, since this is a process note, not an ADR-tied architectural
constraint).

## Done when

- `AGENTS.md`'s "## Project notes" section has a "Checks" note that:
  - names `scan` (Sonar) as a project addition, not a t-workflow check, and
    informational only
  - tells `/t-ship` step 2 to wait for every other check to finish, excluding
    `scan`, before merging

## Explicitly not

Changing `.t-workflow/AGENTS.md`, `.t-workflow/scripts/`, or any other
t-workflow-owned file — those are shared across repos and replaced on `/t-update`.
Changing `.github/workflows/sonar.yml` or Sonar's own configuration.

## Decisions made along the way
- none

## Deviations / notes
- none

## Agents
- plan: claude-code/2.1.270 / claude-sonnet-5
- work: claude-code/2.1.270 / claude-sonnet-5
