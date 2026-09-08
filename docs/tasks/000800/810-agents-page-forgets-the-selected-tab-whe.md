# 810 — Agents page forgets the selected tab when the user leaves and returns
Issue: #810

## Asked
On a project with two or more agent tabs, the agents page should reopen on the tab the
user was last looking at. Today it does not: switch to a second tab, leave the page (to
the issues list, say), come back, and the page lands on a different tab. The page already
records each tab click in the browser's last-console memory (`LastConsoleStore`, #221),
but when it reloads its tab strip it ignores that memory and picks the console with the
latest engine-side `lastAttachedAt` timestamp instead. The engine stamps that timestamp
only when a terminal's WebSocket attaches, and unselected tabs stay attached (they are
merely hidden), so switching tabs never moves it; on re-entry every console reattaches
at once and the winner is whichever connected last, not the one the user chose. The
fix: when no `?session=` handoff names a tab, prefer the project's remembered console
when it is still among the open ones, and fall back to the latest-attached console only
when nothing usable is remembered.

## Done when
- In `ProjectConsoleComponent.loadConsoles`, the tab selected on load is chosen in this
  order: the `?session=` query param when it names an open console; otherwise the
  `LastConsoleStore` entry for the project when it names an open console; otherwise the
  open console with the latest `lastAttachedAt`. A remembered id that is no longer open
  is ignored, not selected.
- A spec in `project-console.component.spec.ts` covers each branch: a remembered console
  still open is selected over a later-attached one; a remembered console that is no
  longer open falls back to latest-attached; `?session=` still wins over the remembered
  console.
- Manual check (human): on a project with two agents, select the second tab, navigate to
  the issues list and back; the second tab is selected. Then repeat with the sidenav "+"
  and project-summary console button paths to confirm they still behave as before.
- `./mvnw -B test` passes (client and engine).

## Explicitly not
- No engine change: `lastAttachedAt` semantics stay as they are, and the engine still
  serves the fallback ordering.
- No change to the `?session=` handoff from the consoles list (#179), the sidenav "+"
  (#370), or the project-summary console button; they already read the same store and
  keep working unchanged.
- Not making tab switches re-attach or otherwise touch the engine timestamp.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- none

## Deviations / notes
- none
