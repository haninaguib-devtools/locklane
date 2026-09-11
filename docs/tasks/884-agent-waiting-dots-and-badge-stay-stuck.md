# 884 — Agent waiting dots and badge stay stuck on a session that is no longer waiting
Issue: #884

## Asked

The "waiting for you" state shown by the sidenav's per-issue and per-project agent dots and by the topbar "agents (N)" badge can get stuck on a session that is no longer waiting, until the page is reloaded. The client's `AttentionStore` (`client/src/app/services/attention-store.ts`) holds a set of waiting session ids and removes an id only when a `consoleAttention` event with `state: "active"` arrives for that exact id. Nothing else ever clears it: the engine's connect-time snapshot (#790, `EventsWebSocketHandler`) sends only sessions currently waiting and the client merges it into its existing set, so a waiting→active or waiting→closed transition missed while the events socket was down (laptop sleep, engine restart or update, a throttled background tab — the #762 heartbeat allows up to two intervals of dead socket) is never undone; `SessionRegistry#close` destroys the process without emitting an `active` transition, so a session closed while waiting stays in the set; and a new or reattached `PtySession` starts `ACTIVE` and `updateAttention` only notifies on a change, so opening, focusing (`markFocused`) or typing into a fresh session emits nothing that would correct a stale id. Two visible consequences: an issue's session id is deterministic (`<projectId>-<issueNumber>-<slug>`, reused by `WorktreeCreationService#startSession`), so closing an agent on an issue while it is waiting and starting a new agent on the same issue shows the new one as waiting right away, on both the sidenav dot and the badge, until it actually rings a bell and then receives input; and the sidenav's `hasAttentionWaitingForProject` matches any `<projectId>-console-*` id in the set, so one project agent closed while waiting keeps the project row marked waiting for as long as any other project agent is open. Make the attention state converge with what the engine knows: reconcile on every reconnect, forget a closed session, and never let a session that is no longer listed as open mark a row as waiting.

## Done when

- On an events-socket reconnect the client's waiting set equals the engine's snapshot, not the union of the two — either the store clears itself on `EventsService.reconnected$` before the snapshot's `consoleAttention` events land, or the snapshot carries the full live set — and a spec in `attention-store.spec.ts` proves a session waiting before a reconnect and absent from the snapshot afterwards reads as not waiting.
- Closing a session drops it from every consumer's waiting state: `SessionRegistry#close` (and the sweeper/shell close paths that go through it) leaves no session id waiting in the client — either by emitting a final `consoleAttention` `active` for it, or by the client dropping ids no longer listed as open when it refreshes on `consolesChanged` — with a unit test on the side that implements it.
- Starting a new agent on an issue whose previous agent was closed while waiting shows the sidenav dot and the badge as not waiting until the new session itself rings or goes quiet; a spec covers the reused-id case.
- A project row is marked waiting only by a project agent that is still open; a spec covers a closed-while-waiting project agent alongside an open one.
- `scripts/check.sh` passes.

## Explicitly not

- The badge's entries stream dying on an HTTP error and freezing the app shell's change detection — a separate defect with its own task.
- Changing the `consoleAttention` wire shape in a way an older client cannot read; an added final `active` on close uses the existing shape.
- The sidenav's `refreshAgentSessionIndicators` running overlapping `forkJoin`s with no `switchMap`, which can land out of order and leave an older open-session set on screen until the next event — a related hardening worth doing while in this file if the fix touches it, not a requirement of this task.

## Decisions made along the way
- Reconnect convergence (done-when #1) implemented as "the store clears itself on
  `EventsService.reconnected$` before the snapshot's own `consoleAttention` events
  land" — `AttentionStore.reset()`, called from a new subscription to
  `reconnected$`. `reconnected$` fires synchronously from the socket's `onopen`
  handler, strictly before any message the reopened socket goes on to deliver
  reaches `apply()`, so the ordering the done-when requires holds without any
  engine change. `reset()` also emits a `changes$` transition to not-waiting for
  every session it drops, mirroring `apply()`'s own contract, so a consumer keyed
  off that stream (e.g. `NotificationService`) reconciles the same way it would for
  a real `active` event.
- Closing a session (done-when #2/#3/#4) implemented as "emitting a final
  `consoleAttention` `active` for it" — `SessionRegistry#close` now broadcasts that
  final transition when the session being closed was `WAITING`, in the exact shape
  the live broadcast already uses. This single choke point (every close path —
  `CodeServerService`, `ProjectAgentSessionService`, `ShellSessionService`,
  `WorktreeCleanupSweeper`, `WorktreeController` — already calls
  `SessionRegistry#close`) also covers done-when #3 (a new session reusing the
  same deterministic id starts `ACTIVE` and emits nothing on its own, so the id
  reads as not-waiting from the close's final broadcast onward) and #4 (a project
  row's `hasAttentionWaitingForProject` reads the same shared store) with no
  further client change needed.

## Deviations / notes
- `client/src/app/services/notification.service.ts` is outside this task's Scope
  and untouched, but it consumes `AttentionStore.changes$`: `reset()`'s emissions
  mean a session still genuinely waiting across a reconnect gets a spurious
  not-waiting-then-waiting pair of transitions (closing, then re-showing, any
  desktop notification already up for it) instead of no transition at all. Judged
  acceptable — no done-when or Scope item covers `NotificationService`'s own
  behavior, and the alternative (a silent clear with no `changes$` emission) would
  leave a stale notification for a session that really did go active while the
  socket was down permanently un-closed, which is worse.
