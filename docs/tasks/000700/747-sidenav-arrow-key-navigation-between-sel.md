# 747 — Sidenav: arrow-key navigation between selected issues
Issue: #747

## Asked
In the project sidenav, clicking an issue row selects it but leaves keyboard focus
wherever it was before. Selecting a row should also give it keyboard focus, so that
pressing Up or Down immediately moves the selection to the previous or next issue in
the visible list, without needing to click again.

## Done when
- Selecting an issue row (by click, or by any other means that already sets
  `isSelected`) moves DOM focus onto that row's element.
- With a sidenav row focused, pressing ArrowDown moves both focus and selection to the
  next visible row in the tree — following the same order the rows currently render in
  (respecting expand/collapse state, the active text filter, and the pinned section),
  including across a project-section boundary. Pressing ArrowUp does the same in
  reverse.
- Navigating this way actually changes the selected issue (i.e. it drives the same
  navigation the row's existing `routerLink` click would), not just a focus-ring move
  with no selection change.
- Arrow keys keep their normal behavior everywhere else in the app — in particular the
  sidenav's own filter `<input>` — this only intercepts Up/Down while a row itself is
  focused.
- Covered by a test exercising: click-to-focus, ArrowDown/ArrowUp moving across sibling
  rows, and across a collapsed/expanded boundary.

## Explicitly not
none

## Decisions made along the way
- none yet

## Deviations / notes
- none
