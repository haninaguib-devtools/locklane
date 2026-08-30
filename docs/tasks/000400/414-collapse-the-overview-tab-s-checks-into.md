# 414 — Collapse the Overview tab's checks into an expandable summary line
Issue: #414

## Asked
On an issue's Overview tab, the `checks` row currently lists every CI check by name on
its own line, all the time, which pushes the rest of the details down the page. Restore
the compact single summary line that was there before #397 — the count of checks and how
many passed, failed, or are still running — and make that line an expand/collapse toggle.
Collapsed is the default: a reader sees one line, and opens it only when they want the
per-check detail that #397 added.

## Done when
- The `checks` row renders one summary line by default, with the per-check list hidden.
- The summary line uses the pre-#397 wording exactly: `no CI runs` when nothing ran;
  `<failing> failing / <passing> passing` when anything is failing;
  `<passing> passing, <pending> pending` when nothing is failing but something is
  pending; `<passing> checks green` otherwise.
- The summary line is a toggle control, not a hyperlink. Activating it expands the row to
  show the per-check list #397 introduced (state marker, check name, link to that check's
  run) and collapses it again.
- The toggle is reachable and operable by keyboard, and communicates its
  expanded/collapsed state to assistive technology.
- The link to the pull request's Checks tab on GitHub — today carried by the summary line
  — moves into the expanded panel as a trailing link. It is no longer part of the
  collapsed line.
- The expanded content is unchanged from what ships today, including the "no CI runs"
  case and checks with no run URL.
- `client` unit tests cover the collapsed default, the expand/collapse transition, and
  each of the four summary-line wordings; `./mvnw -B test` passes.

## Explicitly not
- No change to the checks data model or to anything in `engine/` — the per-check runs
  list from #397 stays exactly as it is.
- No live-refreshing of checks while CI runs; no re-running or cancelling a check from
  the console.
- Remembering the expanded/collapsed state across page loads or between issues is not
  part of this; the row starts collapsed every time.

## Decisions made along the way
- The toggle is a native `<button type="button">` carrying `aria-expanded` and
  `aria-controls`, rather than a div with key handlers — keyboard operability and state
  announcement come from the element itself, with no extra code to get wrong.
- The expanded panel stays in the DOM and is hidden with the `hidden` attribute rather
  than removed by `@if`, so the `aria-controls` reference the toggle carries always
  resolves to a real element.
- The pre-#397 wording was restored verbatim from `checksLabel` as it stood at
  `e6b7f29^`, not rewritten from the issue text, so the four cases match what shipped
  before exactly.

## Deviations / notes
- none
