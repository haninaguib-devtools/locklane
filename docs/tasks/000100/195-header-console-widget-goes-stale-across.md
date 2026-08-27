# 195 — Header console widget goes stale across browser tabs on console open/close
Issue: #195

## Asked
The header "consoles" widget (`ConsoleIndicatorComponent`) only learns that a console
opened or closed through an in-memory RxJS `Subject` local to one running Angular app
instance, so a second browser tab or session watching the same project keeps showing a
stale count until it is manually reloaded. Broadcast console open/close over the
existing server-pushed WebSocket event bus (`EventBroadcaster`/`EventsService`), the
same transport already used for `issuesChanged` and `consoleAttention`, so every open
tab reflects console lifecycle changes live.

## Done when
- Opening or closing a console (from the issue page or the project page) in one browser
  tab is reflected in the header consoles widget in a second, independently open
  tab/session for the same project, without a manual refresh.
- The new event follows the existing `EventBroadcaster`/`EventsService` pattern (same
  transport, same subscription shape as `issuesChanged`/`consoleAttention`).

## Explicitly not
- Making project-page consoles appear in the widget in the first place — tracked in
  #194; this task only propagates changes across tabs for whatever the widget already
  shows (or is made to show by #194).

## Decisions made along the way
- The broadcast fires from `SessionRegistry` — the single choke point both a worktree
  console (`WorktreeController`) and a project console (`ProjectConsoleService`) attach
  and close through — rather than from each REST controller, so both console flavors
  are covered by one change (haninaguib, 2026-08-27).
- "Opened" is gated on the session having no persisted `WorktreeSessionRepository`
  record yet, checked *before* `recordAttach` turns that into an upsert — not on
  whether a live `PtySession` object was just created. A reattach after this process
  restarts creates a new live `PtySession` for an id the repository (and therefore
  every listing) already counted as open; broadcasting there would report a change
  that never happened to the list. "Closed" is gated symmetric: a genuine no-op close
  (an id with neither a live session nor a persisted record) broadcasts nothing,
  matching `close()`'s own documented no-op contract (haninaguib, 2026-08-27).
- The event (`consolesChanged`) does not distinguish open from close, and the client
  does not filter it by `projectId` — every current consumer of the existing local
  `onOpened`/`onClosed` signals already merges the two into one undifferentiated
  "something changed, refetch" trigger with no project scoping, so the remote signal
  matches that behavior exactly rather than adding filtering nothing asked for
  (haninaguib, 2026-08-27).
- The client-side wiring lives in `ConsolesService` itself (folding the remote event
  and `EventsService#reconnected$` into the existing `onOpened`/`onClosed`
  observables) rather than in `ConsoleIndicatorComponent`, so every existing consumer
  of those two observables — including the sidenav's open-console dot, out of this
  task's scope — benefits with no changes of its own (haninaguib, 2026-08-27).

## Deviations / notes
- none
