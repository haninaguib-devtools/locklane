# 696 — Let clicking the usage-widget timestamp also collapse it

Issue: #696

## Asked
In the usage widget, clicking the "usage" row expands a details panel with an
"updated x min ago" footer line. Only the row collapses the panel back down — the
footer has no click handler. Add one so the footer also collapses the widget.

## Done when
- Clicking the "updated x min ago" footer text while the usage panel is expanded
  collapses it, the same as clicking the usage row does.
- Clicking the usage row still expands/collapses as before (unchanged).
- The footer only needs to collapse (it's only rendered while expanded) — it does not
  need to expand anything.

## Explicitly not
- The footer expanding the panel — it is only rendered while already expanded.

## Decisions made along the way
- Made the footer a `<button type="button">` (matching the existing `.usage-row`
  button pattern) rather than adding a click handler to the `<div>`, for keyboard/
  screen-reader accessibility consistency with the row it mirrors (haninaguib,
  2026-09-04).

## Deviations / notes
- none
