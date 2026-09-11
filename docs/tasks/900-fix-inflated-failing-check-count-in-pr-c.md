# 900 — Fix inflated failing-check count in PR checks summary
Issue: #900

## Asked
The console's PR checks summary (`ChecksSummary`, computed in
`CliGhClient.toPullRequestDetail`) overcounts failing checks. It builds pass/fail/pending
counts by iterating every entry in `gh pr view --json statusCheckRollup` and classifying
each with `case "SUCCESS" -> PASSING; case "" -> PENDING; default -> FAILING`
(`engine/src/main/java/dev/locklane/engine/github/CliGhClient.java:141-162`). Two bugs
combine to inflate the failing count:

1. No de-duplication: GitHub's rollup includes every check-run ever posted to the PR,
   including stale entries from a superseded, cancelled, or re-run workflow run. `gh pr
   checks` and GitHub's own PR UI collapse to the latest run per `(workflowName, name)`;
   this code counts every historical entry instead.
2. The `default -> FAILING` branch also catches `SKIPPED`, `CANCELLED`, and `NEUTRAL`
   conclusions, none of which are failures — a legitimately-skipped job (e.g.
   mac-lifecycle's `decide`/skip pattern, ADR-111/ADR-110) is counted as a failure.

Verified against PR #899 (issue #898): the raw rollup has 7 entries — 3 current SUCCESS
checks (`build`, `decide`, `t-workflow`) and 1 legitimately-SKIPPED `mac-lifecycle`
check, plus 3 stale CANCELLED/SKIPPED entries left over from an earlier superseded run.
Run through the current switch that's exactly 4 failing / 3 passing, matching what the
console shows. `gh pr checks 899` shows the true state: 3 pass, 1 skip, 0 fail. Any PR
with a rerun/cancel in its check history, or a conditionally-skipped job, hits this.

Fix `CliGhClient.toPullRequestDetail` to: dedupe `statusCheckRollup` entries by
`(workflowName, name)`, keeping only the most recent by `startedAt`/`completedAt`; and
stop counting a `SKIPPED` or `NEUTRAL` conclusion as failing.

## Done when
- `CliGhClient.toPullRequestDetail` dedupes `statusCheckRollup` entries by
  `(workflowName, name)` to the most recent before classifying.
- A `SKIPPED` or `NEUTRAL` conclusion no longer counts toward `failing`.
- A unit test in `CliGhClientTest` reproduces PR #899's raw rollup shape (stale
  CANCELLED/SKIPPED entries from a superseded run, plus current SUCCESS/SKIPPED
  entries) and asserts the resulting `ChecksSummary` is 3 passing / 0 failing / 0
  pending, not 4 failing / 3 passing.
- `./mvnw -pl engine -am test -Dtest=CliGhClientTest` passes.

## Explicitly not
- Does not touch `CheckRun`'s three-state model (`PASSING`/`FAILING`/`PENDING`) or
  add a new state for skipped checks — a skipped/superseded check is simply excluded
  from the counts, not surfaced as its own category.
- Does not change how the client (`overview-tab` component) renders the summary.

## Decisions made along the way
- Dedup key is `workflowName + "" + name` (the same `name`-or-`context` fallback
  the existing `checkName` helper already used for the older status-context shape);
  recency compares `completedAt` (falling back to `startedAt`) as raw ISO-8601 strings,
  which sort correctly since gh always emits them in UTC `Z` form.
- A kept entry whose conclusion is `SKIPPED`/`NEUTRAL` is dropped from `runs` entirely,
  not added with one of the three existing states — matching the issue's non-goal that
  a skipped/superseded check isn't its own category.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
