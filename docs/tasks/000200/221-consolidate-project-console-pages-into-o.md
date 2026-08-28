# 221 — Consolidate project console pages into one, using default agent
Issue: #221 · Part of: #218

## Asked
Project-level consoles were split across two overlapping pages:
`ConsolesPageComponent` at `/projects/:projectId/consoles` ("Open consoles") only
listed existing consoles for reattachment, while `ProjectConsoleComponent` at
`/projects/:projectId/console` ("New issue console") started or attached to one
specific console. This collapses them: the list page is removed, the remaining page
is retitled "Project console", and both the sidebar "+" and the project page's
console button target it directly — creating a console when none exists, or
jumping back into the one the user most recently interacted with when one or more
already exist.

## Done when
- `/projects/:projectId/consoles` (`ConsolesPageComponent`) and its route are
  removed.
- The page at `/projects/:projectId/console` (`ProjectConsoleComponent`) is
  retitled "Project console".
- The sidebar "+" (`sidenav.component.ts` `openNewConsole`) still creates a console
  but uses the default agent from #219 instead of hardcoding `'claude'`.
- The project page's console button (`project-summary.component.html`) reads "Open
  console" and creates a console (same behavior as the sidebar "+") when the
  project has no open console yet.
- When the project has one or more open consoles, that button instead reads "Open
  consoles" and navigates to the console the user most recently interacted with.
- "Most recently interacted with" is tracked client-side (localStorage, alongside
  the existing `AgentStore` pattern) and falls back to the last console in the tab
  list when no recency data has been recorded yet.
- A human confirms in the browser: with zero, one, and multiple open project
  consoles, both entry points behave as above.

## Explicitly not
- none

## Decisions made along the way
- Added a new `LastConsoleStore` (localStorage, keyed by project id) rather than
  reusing the project-console page's existing "most recently attached" sort
  (server-provided `lastAttachedAt`): the issue explicitly asks for a client-side
  recency signal alongside the `AgentStore` pattern, and it lets the project
  summary's button navigate straight to the right session without first loading
  the full console list itself (haninaguib, 2026-08-27).
- `ProjectConsoleComponent` records into `LastConsoleStore` on every point where
  `selected` changes — initial load/attach, a tab click, starting a new console,
  and closing one's fallback — so "most recently interacted with" reflects actual
  use of the page, not just the moment the button was clicked (haninaguib,
  2026-08-27).
- The project summary's console button validates a remembered session id against
  the freshly-fetched open-consoles list before using it (same pattern
  `ProjectConsoleComponent` already used for its `?session` query-param handoff),
  falling back to the last entry in that list — covering both "never recorded" and
  "recorded but since closed" (haninaguib, 2026-08-27).
- Replaced the summary's `<a class="consoles-link">` with a `<button
  class="console-button">`: the target now depends on state fetched at click time
  (start vs. navigate), which a static `routerLink` can't express (haninaguib,
  2026-08-27).

## Deviations / notes
- none
