# 143 — Sidebar: move the console dot to the left of the status pill
Issue: #143

## Asked
Issue rows in the sidebar (`client/src/app/components/sidenav/`) show a small blue dot
when the issue has an open console (added in #108). Today the dot sits to the right of
the OPEN/CLOSED status pill. Move it so the dot appears to the left of the status pill
instead.

## Done when
- On an issue row with an open console, the console dot renders immediately to the left
  of the status pill (OPEN/CLOSED etc.), not after it.
- Rows without an open console look unchanged.
- Existing sidenav tests pass (`./mvnw -B test` / the client test suite, as wired in CI).
- Manual check: open a console on an issue and confirm the dot is left of the status pill
  in the sidebar.

## Explicitly not
No change to the dot's behavior, color, or the console popup — position only.

## Decisions made along the way
- none

## Deviations / notes
- none
