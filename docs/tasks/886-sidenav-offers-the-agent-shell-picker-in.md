# 886 — Sidenav + offers the agent/shell picker instead of starting the default agent
Issue: #886

## Asked

The sidenav's per-project "+" button (`client/src/app/components/sidenav/sidenav.component.html`, `openNewAgentSession`) navigates to the project's agents page with a bare `?new=1` query parameter, and that page (`ProjectAgentSessionComponent`) then starts the Settings default agent — one click, no choice. The agents page's own tab strip "+" (`AgentSessionTabsComponent`, #757/#876) instead opens a picker listing every installed agent plus Shell, and starts exactly what was picked. Give the sidenav "+" that same picker, so a user can choose an agent or a shell from the sidenav. Decisions taken: (1) the picker's markup and state (open/close, outside click, Escape) are extracted out of the tab strip into one shared picker component used by both the tab strip and the sidenav, not duplicated; (2) the tab strip's rule applies to the sidenav — the picker shows only when there are two or more choices (installed agents plus Shell), and with Shell the only choice the click launches directly; (3) the sidenav keeps handing off to the agents page rather than minting anything itself (#370), so the `?new` parameter carries the choice (an agent id, or shell) and the page's start path branches on it, including the pending-flag path that guards a "+" click for a project other than the one showing (#439). A picked Shell is a main-checkout shell on the agents page, the same one its tab strip's Shell entry mints (`ShellsService.open` with the project's `workareaPath`), selected as a tab once open. The sidenav triggers `DefaultAgentStore.refreshInstalled()` on init so the choices are known before the first click.

## Done when

- Clicking the sidenav "+" on a project with at least one installed agent opens a menu listing each installed agent and Shell; choosing an agent lands on the project's agents page with a new tab running that agent, and choosing Shell lands there with a new main-checkout shell tab selected.
- With no installed agent known, the click launches a shell directly with no menu, matching the tab strip's own single-choice behaviour.
- The menu is not clipped by the sidebar's scrolling container at any sidebar width, and closes on a choice, an outside click, or Escape.
- A "+" choice made for a project other than the one currently showing on the agents page still starts the chosen kind on the target project, not the previous one (the #439 race), covered by a spec.
- The tab strip's "+" behaves exactly as before, driven by the extracted shared picker component; its existing specs pass unchanged or with only import changes.
- Specs cover: the sidenav menu's choices, the agent and shell handoffs, the direct-launch case, and the agents page branching on the carried choice.
- `scripts/check.sh` passes.

## Explicitly not

- No engine change: agents and shells are opened through the existing endpoints.
- Issue rows keep their current behaviour; only the project row's "+" gains the picker.
- No new preference for a "default kind"; the Settings default agent stays what it is and is one of the menu's entries, not a bypass of it.
- The project page's own "Open agent" and "Open shells" buttons are unchanged.

## Decisions made along the way

- The shared picker (`client/src/app/components/agent-shell-picker/`) owns its
  dropdown's position as `position: fixed`, computed from the button's own
  `getBoundingClientRect()` on open (with a capture-phase scroll listener that
  closes it), rather than `position: absolute` anchored in flow the way the tab
  strip's original picker was. The sidenav's project list scrolls inside its own
  `.sidenav-scroll` container; an in-flow absolute menu there can render partly
  outside the currently-scrolled viewport, which is exactly the clipping the
  Done-when bullet calls out. Fixed positioning escapes that regardless of which
  host embeds the picker, so both hosts share the one mechanism.
- The picker's variant classes are named `picker-tab`/`picker-sidenav` (via
  `HostBinding`), not the more obvious `tab`/`sidenav` — a first pass used those and
  broke several `agent-session-tabs.component.spec.ts` tests that `querySelectorAll('.tab')`
  across the whole rendered DOM: the host element itself matched, since a raw DOM
  query doesn't respect Angular's per-component CSS scoping.
- `?new`'s value now carries the choice itself (an installed agent's id, or the
  literal `shell`) rather than always being `1`. `1` (and an empty string) still
  parse as "the Settings default" so every pre-existing `?new=1` handoff and test
  keeps working unchanged — this is additive, not a breaking change to the param.

## Deviations / notes

- `AgentSessionTabsComponent`'s own picker logic (`pickerOpen`, `offersPicker`,
  `agentChoices`, `plusClicked`, `pickAgent`, `pickShell`, `closePicker`) moved
  into the new shared `AgentShellPickerComponent` per the issue's own decision 1
  ("not duplicated"). This is a bigger change to `agent-session-tabs.component.spec.ts`
  than "import changes only": the several bare-constructor unit tests that called
  those methods/properties directly on `new AgentSessionTabsComponent()` no longer
  have anything to call, since the picker's open/close state now genuinely lives in
  a real Angular component (its `@HostListener`s need real rendering, which a bare
  constructor never does). Those tests' coverage was relocated to
  `agent-shell-picker.component.spec.ts` (same assertions, same bare-constructor
  style, new class); `agent-session-tabs.component.spec.ts` keeps its DOM-rendered
  picker tests (which never referenced the internal state directly) unchanged, plus
  one new DOM-based test for the tab-menu/picker mutual exclusion that a removed
  bare test used to cover directly. Net effect: the same behaviours are still
  tested, once each, just not 100% of the diff is "imports only" as the bullet's
  ideal read.
- Ran `scripts/check.sh` directly (BUILD SUCCESS: 1031 engine tests, 973 client
  tests, all passing) rather than relying on CI alone, since this touches three
  component trees plus a new one.
