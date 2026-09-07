# 760 — Announce project creation and reload the sidenav on events for unlisted projects
Issue: #760 · Part of: #759

## Asked
When a project is created in one browser window, every other open window's sidenav
should show it, and later activity in that project (issues opened, clone finishing)
should reach those windows too. Today the engine broadcasts nothing on project
creation, and the sidenav silently ignores events for a project it does not already
list — `refreshProject` (the `issuesChanged` handler) returns on a miss, and
`applyProjectStatusEvent` parks a READY event for an unknown project in
`pendingStatus` on the assumption that a reload is already in flight, which is only
true in the creating window. The result is a project, and all of its issues,
invisible everywhere else until a manual page reload.

Fix both sides: the engine broadcasts a `projectCreated` event (`projectId`) from the
same choke point that already broadcasts `projectStatus` / `projectDeleted` in
`ProjectCheckoutService`; the sidenav reloads its project list on `projectCreated`,
and treats an `issuesChanged` or `projectStatus` event naming a project it does not
have as a signal to reload the list rather than dropping the event. The reload must
go through the existing `refresh()` queueing (#738) so it never races an in-flight
load. The `refreshProject` callback must also not write into a stale array index if
`sections` was replaced while its fetch was in flight — look the project up again by
id when the response lands.

## Done when
- `EventsService` in `client/src/app/services/events.service.ts` exports a typed
  `ProjectCreatedEvent` and its guard, alongside the existing `ProjectStatusEvent`.
- `ProjectCheckoutService` broadcasts `projectCreated` when a project row is inserted
  (both the clone-existing-repo and create-new-repo paths), with a unit test asserting
  the broadcast.
- Sidenav unit tests (`sidenav.component.spec.ts`) cover: (a) a `projectCreated` event
  triggers a list reload; (b) an `issuesChanged` for an unlisted project triggers a
  list reload instead of being ignored; (c) a `projectStatus` READY for an unlisted
  project triggers a list reload and, once the row exists, its tree fetch; (d) a
  `refreshProject` whose response lands after `sections` was replaced updates the
  correct project's row.
- `./mvnw -B test` passes.
- Manual check (human judgment): two windows open, create a project in window A, open
  its console there and run `gh issue create`; window B shows the project and then the
  issue without a reload.

## Explicitly not
- Any change to `EventBroadcaster`'s threading (sibling task) or to the client's socket
  liveness (sibling task).
- Changing how the overview page or project page learn about new projects.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- **Event-driven reloads do not bypass the engine's issue cache** (agent, 2026-09-07).
  `refresh()` gained an optional `fresh` argument (default `true`, so the refresh
  button, `revealProject`, retry and delete behave exactly as before) and the
  `projectCreated` / unlisted-`issuesChanged` / unlisted-`projectStatus` reloads pass
  `false`. The issue asks for the reload to go through `refresh()`'s queueing, which
  it does; but `refresh()` was hard-wired to `fresh=true` (#545), and a cache-bypassing
  reload in *every* open window per event would cost one GitHub fetch per project per
  window — the event says the list changed, not that every cached tree is stale. A
  queued run is fresh if any of its queued callers wanted it fresh.
- **An unlisted `projectStatus` starts a reload only when none is in flight** (agent,
  2026-09-07). The event is still parked in `pendingStatus` either way (#729), so a
  reload already running carries and applies it exactly as before; starting a second
  one behind it would be the re-polling #729 removed (and its test asserts against).
  `projectCreated` always precedes a project's first `projectStatus` on the socket
  (the engine broadcasts it before dispatching the clone), so in every window a reload
  that will carry the row is already in flight or queued by the time the status event
  arrives. An unlisted `issuesChanged` and a `projectCreated`, which have nothing to
  park, always call `refresh(false)` and let the queueing coalesce them.
- **A focused window (#286) ignores events about any other project** (agent,
  2026-09-07). It lists exactly one project, so a reload could never carry another;
  without this guard every event about another project would reload the focused
  window for nothing.
- **`projectCreated` is broadcast before the clone is dispatched**, from one private
  `announceCreated` choke point called right after each `repository.create` (agent,
  2026-09-07) — the same shape as `markReady` / `markFailed` / `deleteRow`. The
  import-path test pins the order with Mockito `InOrder`: `projectCreated` first,
  then `projectStatus`.

## Deviations / notes
- Two existing sidenav specs asserted the exact behaviour this task changes and were
  rewritten rather than deleted: "an issuesChanged event for a project not currently
  loaded is ignored" became Done-when (b); "a projectStatus event for a project not
  currently loaded leaves the loaded rows alone (#721)" now flushes the reload the
  event starts and asserts the loaded rows are still untouched when the list does
  not carry the project. "a held projectStatus event is dropped once its project is
  deleted (#729)" no longer calls `refresh()` by hand — the unlisted event starts that
  reload itself now — and its assertion is unchanged.
- Beyond the four cases the issue names, the spec also covers a `projectCreated`
  arriving during an in-flight refresh (queued, not raced — the #738 requirement) and
  the focus-mode guard above.
- Multi-account note, not acted on: `EventBroadcaster` fans every event out to every
  connected session, while `/api/projects` lists only the caller's own projects
  (ADR-105). Another account's `projectCreated` / unlisted events therefore cost this
  account's windows one (cached) reload per event that finds nothing new. Bounded and
  harmless today; a per-owner broadcast would be its own task if it ever matters.
