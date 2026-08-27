# 170 — Sidenav issue rows are real links (open in new tab works)
Issue: #170

## Asked
Each issue row in the sidenav navigates only through a JavaScript click handler (a
`div` with `(click)`), so the browser does not treat it as a link: right-click →
"open in new tab", middle-click, and ctrl/cmd+click do nothing, and no URL preview
shows on hover. Make each issue row a real HTML anchor pointing at that issue's route
(`/projects/<projectId>/issues/<number>`, via Angular `routerLink`) so all native
browser link behaviors work, while a plain left-click keeps behaving exactly as today
(in-app navigation, no full page reload).

## Done when
- Each issue row in the sidenav (including pinned rows and nested children) renders
  as an `<a>` whose `href` resolves to `/projects/<projectId>/issues/<number>`.
- Middle-click / ctrl+click / right-click → "open in new tab" opens the issue in a
  new tab, landing on the same view as in-app selection (human-judged in the running
  app; works thanks to the #162 refresh fix).
- Plain left-click still selects the issue in-app with no full page reload, and the
  row's existing controls — expand/collapse twisty, kebab menu, pin/unpin — still
  work without triggering navigation.
- The active-row highlight and open-console indicators render as before.
- `client` build and existing tests pass, with the sidenav component spec updated
  for the new markup.

## Explicitly not
- Project section headers (which navigate to `/projects/<id>/issues`) stay as they
  are; only issue rows are converted.
- No visual redesign of the rows — anchors are styled to look exactly as the rows do
  today.

## Decisions made along the way
- The sidenav's `selectedChange` output and `AppComponent.select()` are removed
  rather than kept alongside `routerLink` (Claude, 2026-08-27): `select()` only ever
  did `router.navigate` to the exact URL the row's `routerLink` now points at, so
  keeping both would navigate twice per click and leave a dead output. The issue's
  Scope anticipates this ("minor wiring in `app.component.*` only if the selection
  flow requires it").

## Deviations / notes
- The row controls' click handlers (twisty, kebab, pin) gained `preventDefault()`
  next to their existing `stopPropagation()`: Chrome follows the enclosing anchor's
  href when a nested button is clicked, so without it every control click became a
  full page load. Caught by Karma ("Some of your tests did a full page reload!") on
  the first test run.
