# 33 — Add a refresh button to the sidenav issues list
Issue: #33 · Part of: #1

## Asked
The sidenav's issue list only loads data once, on component init. Add a refresh
button/icon to the sidenav that re-fetches the issue tree and updates the list in
place, with a loading indicator while the request is in flight.

## Done when
- A refresh control is visible in the sidenav.
- Clicking it re-calls the tree endpoint and the list re-renders with the response
  (including any changes made on GitHub since the last load), without a full page
  reload.
- A visible loading/spinner state shows while the refresh request is in flight, and
  the control is disabled or debounced against being triggered again mid-flight.
- Existing sidenav behavior (resizing, pinned section, initiative nesting, filter)
  is unaffected.

## Explicitly not
- Automatic/periodic polling refresh — this is a manual, user-triggered refresh only.
- Refreshing other parts of the UI (issue detail, terminal sessions) — sidenav list
  only.

## Decisions made along the way
- none

## Deviations / notes
- none
