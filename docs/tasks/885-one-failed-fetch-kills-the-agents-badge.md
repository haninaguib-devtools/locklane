# 885 — One failed fetch kills the agents badge stream and freezes the app shell until reload
Issue: #885

## Asked

The topbar "agents (N)" badge (`client/src/app/components/agent-session-indicator/agent-session-indicator.component.ts`) builds its `entries` signal with `toSignal` over a stream that re-fetches every visible project's `/consoles`, `/issues` and `/console/sessions` lists on each open, close, rename or reconnect, and neither that stream nor `AgentSessionEntriesService.fetchEntries` catches errors. One failed request errors the whole chain permanently: no later event is ever fetched, and Angular's `toSignal` (default `rejectErrors: false`) stores the error and rethrows it on every read of `entries()`. That read happens in the topbar template, so from then on every change-detection pass throws at the topbar and aborts, and everything rendered after it in `app.component.html` — the sidenav's agent dots and clone states, the main content — stops refreshing too, until the page is reloaded. A guaranteed trigger exists: `CurrentProjectService.projects$` is fetched once at construction and refreshed only by the project page's accent-color save, never on `projectDeleted`, `projectCreated` or a reconnect, so after a project is deleted the next `consolesChanged` or reconnect makes the badge request `/api/projects/<deleted>/issues`, which `IssueController#list` answers with 404, and the stream is dead. Any transient failure while a fetch is in flight (the engine restarting during an update, a 5xx) does the same. Make the badge survive a failed fetch and keep the shared project list current, so a single HTTP error can never freeze the app shell.

## Done when

- A failed fetch for one project leaves the badge showing its last good entries (or the other projects' entries) and the stream keeps reacting to later `onOpened`/`onClosed`/`onRenamed`/reconnect triggers; a spec in `agent-session-indicator.component.spec.ts` or `agent-session-entries.service.spec.ts` fails one project's request and asserts a later trigger still refreshes the badge.
- Reading `entries()` never throws: no error reaches `toSignal`'s error channel (per-project or per-fetch `catchError`), or `rejectErrors: true` is set with the error routed to the app's `ErrorHandler`.
- `CurrentProjectService`'s project list follows `projectCreated` and `projectDeleted` events and re-fetches on `EventsService.reconnected$`, so a deleted project drops out of `projects$` without a page reload; a spec covers deletion.
- After deleting a project and then opening an agent in another project, the badge count updates and the sidenav dot for the new agent appears without a reload.
- `scripts/check.sh` passes.

## Explicitly not

- Stale "waiting" attention state surviving a reconnect or a session close — a separate defect with its own task.
- Retrying failed requests or surfacing the failure in the badge's UI; staying on the last good value and recovering on the next event is enough.
- Changing what the engine returns for a deleted project's `/issues`.

## Decisions made along the way
- Per-project isolation lives in `AgentSessionEntriesService.fetchEntries` (`catchError` per project to `[]`), so one project's 404/5xx keeps the other projects' entries; last-good-value retention for a whole-fetch failure lives in the indicator component (`catchError` to `EMPTY`, keeping the stream alive for the next trigger) rather than `rejectErrors: true`, so no error is ever routed anywhere.
- `CurrentProjectService` re-fetches on `projectCreated`/`projectDeleted` and on `EventsService.reconnected$`, mirroring the sidenav's event handling; `refresh()` swallows HTTP errors so a failed list fetch keeps the last good `projects$`.
- No change to `notification.service.ts`: it shares `fetchEntries`, which now never errors, so its subscribes are safe unchanged.
- New specs fail the last of the three per-project requests (`/console/sessions`), because failing an earlier one cancels the later HTTP calls, which `HttpTestingController` still reports as open.

## Deviations / notes
- none

## Checks run
- `scripts/check.sh` — FAIL (client: TOTAL 960 SUCCESS; engine: 4 failures in `ProjectCheckoutServiceTest` credential-helper/gh-token tests. This diff touches no engine file, so it cannot cause them: they shell out to git and observe ambient config, and this environment injects a credential helper via `GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_*`/`GIT_CONFIG_VALUE_*` plus `GH_TOKEN`.)
