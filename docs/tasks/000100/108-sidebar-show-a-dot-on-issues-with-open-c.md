# 108 — Sidebar: show a dot on issues with open consoles
Issue: #108

## Asked
Add a small visual indicator (a plain colored dot, no count) next to an issue row in
the sidebar (`client/src/app/components/sidenav/`) when that issue has one or more
open consoles. Mirrors the sidebar's existing minimal glyph style (▸ twist arrow, ⋮
kebab) rather than adding a numeric badge.

## Done when
- An issue row in the sidebar shows a small dot (~7px, filled, using a dedicated
  color distinct from any existing status/label color) when it has at least one open
  console for that issue.
- The dot disappears when the issue has zero open consoles.
- The dot updates live as consoles open/close, without requiring a manual sidebar
  refresh.
- Manual check: open a console on an issue, confirm its sidebar row shows the dot;
  close it, confirm the dot disappears.

## Explicitly not
No open-count number on the dot — presence only. No changes to the console popup
itself (tracked separately in #105).

## Decisions made along the way
- #105 (which was going to add `ConsolesService`'s "opened" notification) is still
  open, so this task adds `onOpened`/`notifyOpened()` to `ConsolesService` itself,
  since the issue's own scope already covers `consoles.service.ts` and its Done-when
  text anticipates extending it if the notification doesn't exist yet.

## Deviations / notes
- Wiring `notifyOpened()` into the actual "open console" success path required a
  one-line call in `main-content.component.ts`, which sits just outside this issue's
  declared Scope (`sidenav/`, `consoles.service.ts`). Without it the dot could never
  update live, which is a stated Done-when criterion, so the call was added — the
  smallest change that makes the in-scope `notifyOpened()` reachable at all.
