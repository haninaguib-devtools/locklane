# 65 — Add close button to console tabs
Issue: #65

## Asked
Each console tab in the header UI gets a small close (x) button. Clicking it closes
that specific console session, the same way the tab would be closed today (if any
such mechanism exists), removing it from the tab bar.

## Done when
- Every open console tab shows a small "x" control.
- Clicking the "x" closes only that tab's session and removes the tab from the bar.
- Closing a tab does not affect other open tabs' sessions.
- If the closed tab was the active tab, another remaining tab (or the empty state)
  becomes active/shown.

## Explicitly not
- Terminating the PTY session server-side. No server API for that exists today —
  `SessionRegistry`/`PtySession` treat a session as durable and reattachable
  independent of any client connection (ADR-002, and #7's explicit done-when that a
  closed websocket must never stop the session). Closing a tab here only removes it
  from this browser's client-side list and disconnects its websocket; the session
  stays live and reattachable (e.g. by reopening it, or from another browser).

## Decisions made along the way
- Confirmed with a read-only backend search that no per-session close/kill endpoint
  exists, and the codebase's established pattern is connection-close ≠ session-close
  — so scoped this to client-side tab removal only, matching the issue's Scope
  ("client console tab UI").

## Deviations / notes
- none
