# 757 — Agent picker on the console tab strip's open button
Issue: #757

## Asked
When the user opens a new console from the tab strip's open button — the "+" on the
project consoles page and the "Console" button on an issue page — they choose which
agent CLI runs in it instead of silently getting the Settings default. Clicking the
button opens a small picker listing the agents the engine detected on its host `PATH`
(the same list the Settings dialog's "Default agent" section shows, from
`GET /api/agents/installed`); picking one starts the console with that agent, exactly
as the button does today with the default. When exactly one agent is installed there is
nothing to choose, so no picker appears and that agent is used directly. Every other
place a console starts — the sidenav's per-project "+", the console page's empty-state
auto-start, the template-seeded console, and the project summary's "Open console"
button — keeps launching with the Settings default agent unchanged.

## Done when
- On the project consoles page, with two or more installed agents, clicking the tab
  strip's "+" shows a picker with one entry per installed agent (id and label from
  `DefaultAgentStore.installed()`), and no console starts until an entry is chosen.
  Choosing one emits the existing `open` event with that agent, so the console starts
  and attaches with `cmd=<chosen id>`; dismissing the picker (outside click or Escape)
  starts nothing.
- The same holds for the issue page's "Console" button (the same
  `ConsoleTabsComponent`, `openLabel="Console"`).
- With exactly one installed agent, the button starts a console with that agent
  immediately, with no picker.
- With zero installed agents (the fetch returned an empty list, or has not resolved
  yet), the button behaves as today: it starts a console with
  `DefaultAgentStore.agent()` immediately.
- The picker never offers `shell`; its entries are exactly the installed list.
- The sidenav "+" (`sidenav.component.ts` `openNewConsole` → `?new=1` →
  `startDefault()`), the empty-state auto-start and retry in
  `project-console.component.ts`, the template-seeded console, and
  `project-summary.component.ts`'s "Open console" button are unchanged and still use
  `DefaultAgentStore.agent()` — verified by `git diff` showing no behavioural change at
  those call sites.
- Whichever component shows the picker triggers `DefaultAgentStore.refreshInstalled()`
  so the installed list is populated before the button is first used; a picker is never
  shown with stale or empty data when the engine has agents.
- Unit specs in `console-tabs.component.spec.ts` cover: two-plus agents → picker shown
  and `open` emitted only after a choice, with the chosen agent; one agent → immediate
  emit with that agent, no picker; zero agents → immediate emit with the default agent.
- `./mvnw -B test` passes (the diff touches `client/`, a build input), and
  `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No engine change: the console-creation endpoint stays agent-free and the agent still
  reaches the engine only as the WebSocket attach `cmd` parameter, as today.
- No picker on the sidenav "+", the empty-state auto-start, the template-seeded console,
  or the project summary's "Open console" button — all keep the Settings default.
- `shell` is not offered in the picker; the overview page's shell launcher is unchanged.
- No change to the Settings dialog or to what the "Default agent" setting means.
- No re-detection of installed agents at runtime; the boot-time `PATH` scan is used as is.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The installed list reaches `ConsoleTabsComponent` as a plain `installedAgents` input
  bound by each host from `defaultAgentStore.installed()`, not by injecting the store
  into the strip: the strip's existing specs construct it with bare
  `new ConsoleTabsComponent()` and other suites render it without an HttpClient
  provider (#447), and an input keeps both working; the default (an empty list) is
  exactly the zero-agents fallback the issue asks for. (Claude, 2026-09-07)
- The "triggers `refreshInstalled()`" criterion is met by the two hosts' existing
  `ngOnInit` calls (#698), which already run before the button can be clicked; no new
  call was added, since the store de-duplicates the fetch per app load anyway.
  (Claude, 2026-09-07)
- The picker's element carries `class="agent-picker"`; the project-console spec that
  asserted the "+" opens no popover (#256, `.picker`) is rewritten to assert the new
  behaviour — with the two agents that spec already seeds, the "+" shows the picker and
  starts nothing until an entry is chosen. (Claude, 2026-09-07)

## Deviations / notes
- none
