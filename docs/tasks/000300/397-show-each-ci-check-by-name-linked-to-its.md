# 397 — Show each CI check by name, linked to its run
Issue: #397

## Asked
The Overview tab's checks row said things like "1 failing / 8 passing" without saying
what those checks were or where to look. Name each CI check and link it to its log: one
row per check with its name, a pass/fail/running marker, and a link straight to that
job's run on GitHub, plus a summary line linking to the pull request's Checks tab. The
engine already read every check out of GitHub's status rollup and threw away everything
except the outcome — it keeps the name and the link instead, and the API carries them to
the page.

## Done when
- `ChecksSummary` carries a list of individual checks, each with a name, a state of
  passing/failing/pending, and the URL of its run; `CliGhClient` fills them from
  `statusCheckRollup`'s `name`/`context` and `detailsUrl` rather than discarding them,
  and `IssueDetail` carries them to the client.
- `issue.model.ts` mirrors the new engine shape.
- The Overview tab renders one row per check — name, status marker, link to its run —
  followed by a summary line ("1 failing, 8 passing, 1 running") linking to
  `<repoWebUrl>/pull/<prNumber>/checks`.
- With no PR or no check runs, the row reads "no CI runs" as it does today; a check whose
  run URL is missing renders as plain text, not a dead link.
- Tests cover the rollup parse (a passing, a failing, and a pending entry, and one with
  no `detailsUrl`) and the rendered rows.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Live-refreshing the checks while CI runs; the page loads them as it does today.
- Re-running or cancelling a check from the console.
- The header/tags/ordering changes in #396.

## Decisions made along the way
- The list of checks lives on `ChecksSummary` itself (a fourth component, `runs`) rather
  than in a value alongside it — the issue allowed either, and everything that already
  carries the summary (`GhPullRequestDetail`, `IssueDetail`, the client model) then
  carries the checks with no further plumbing (Claude, 2026-08-30).
- A new `CheckRun` record holds name/state/url, with the three state strings as
  constants, so the engine and the client agree on the exact words `passing`/`failing`/
  `pending` (Claude, 2026-08-30).
- The link falls back to a status context's `targetUrl` when there is no `detailsUrl`:
  the issue names `detailsUrl` (what a check run carries), and an old-style status
  context would otherwise render as an unlinked name for no reason (Claude, 2026-08-30).
- The summary line's wording changed to the issue's own example — comma-joined, with
  zero-count kinds left out and "running" for pending — replacing the old
  "1 failing / 8 passing" / "3 checks green" forms. Their tests were rewritten to match
  (Claude, 2026-08-30).
- `toPullRequestDetail` became package-private so the rollup parse can be tested
  directly, rather than putting a fake `gh` subprocess behind it (Claude, 2026-08-30).

## Deviations / notes
- Three spec files outside the task's declared scope —
  `client/src/app/app.component.spec.ts`,
  `client/src/app/components/main-content/main-content.component.spec.ts`, and
  `client/src/app/services/issues.service.spec.ts` — each build an `IssueDetail`
  fixture and stop compiling the moment `ChecksSummary` gains a field. Each got the same
  one-token addition (`runs: []`) and nothing else; without it the scoped change cannot
  build. Same for the one fixture in
  `engine/src/test/java/dev/locklane/engine/github/IssueDetailServiceTest.java`.
- The marker glyph carries `aria-hidden` with the state repeated in a visually hidden
  span, so a screen reader hears "failing build" rather than a bare "✕".
