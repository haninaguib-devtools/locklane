# 30 — Client: tabbed console UI with location/agent picker and multi-tab reconnect
Issue: #30 · Part of: #28

## Asked
The engine (#29) now supports several independent console sessions per issue —
including several on the main checkout — each launched with a chosen agent
(`claude`, `codex`, or a plain shell). The client still shows one terminal at a
time, keyed to one worktree id, and only ever reconnects `worktreeIds[0]` on
load. Build the UI for the new model: one tab per open console under an issue, a
"+" control that opens a new console after picking its location (main checkout
vs worktree) and agent, and reconnecting every tab that was open on reload.

## Done when
- Each open console for an issue shows as its own tab. Tab header shows
  location, an index only when more than one of that type is open ("main",
  "main 2", "wtree", "wtree 2"), and the chosen agent (e.g. "wtree · claude").
- A "+" control opens a new console after the user picks its location and agent.
- Reloading the app restores every console tab that was open for the issue, not
  just `worktreeIds[0]`.
- Manual verification in the browser: two consoles on main plus one on a
  worktree for the same issue, reload, all three tabs come back with scrollback
  intact.

## Explicitly not
- No engine changes — #29 already added the `worktree=` request param and the WS
  `?cmd=` param this consumes.
- No persistence of the chosen agent on the engine (out of #29's scope too); the
  client remembers it locally so tab labels survive a reload on the same
  browser.

## Decisions made along the way
- Every tab's terminal stays mounted and connected, hidden with CSS when not
  selected, instead of one terminal that re-keys on tab switch — this is what
  makes "all tabs reconnect on load with scrollback intact" true, and it removes
  the old clear-and-reconnect on tab change. (Claude, 2026-08-25)
- The chosen agent is stored per session id in `localStorage`
  (`agent-store.ts`, same pattern as `pin-store.ts`), because the engine
  deliberately does not persist a session's launch command (#29 record). A
  session whose agent is unknown (other browser, cleared storage) shows just its
  location. (Claude, 2026-08-25)
- Location is derived from the session id shape the engine mints: ids containing
  `-main-` are main-checkout consoles, everything else is a worktree session.
  (Claude, 2026-08-25)

## Deviations / notes
- none
