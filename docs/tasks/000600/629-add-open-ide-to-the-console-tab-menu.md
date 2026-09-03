# 629 — Add "Open IDE" to the console tab menu
Issue: #629 · Part of: #627

## Asked
Add an "Open IDE" item to a console tab's kebab (⋮) menu, alongside the existing
Shell/Folder/Close items, that calls the engine's new code-server endpoint for that
tab and opens the returned URL in a new browser tab — the same shape as the existing
"Shell" action (mint a session/URL server-side, then `window.open` it).

## Done when
- `console-tabs.component.ts`/`.html` has a new "Open IDE" menu entry next to
  Shell/Folder/Close, visible under the same gating as the existing local-only items
  (matching, not widening, `isLocalHost`'s current scope).
- Clicking it calls the engine endpoint for that tab's console id and opens the
  returned URL via `window.open(url, 'locklane-ide')` on success.
- A component test covers the menu item's presence and that it triggers the expected
  service call.

## Explicitly not
- Any engine-side process management (covered by #628, which this task is blocked on
  and merges ahead of it on the shared integration branch).

## Decisions made along the way
- `ConsolesService` gets a new `openIde(projectId, id)` method rather than a new
  sibling service — it already has `reveal`, hitting the same controller
  (`ConsolesController`) `open-ide` now also lives on, so the existing service fits
  without adding a parallel one (agent, 2026-09-03).
- `openIdeAt` lives directly on `ConsoleTabsComponent`, calling an injected
  (`@Optional()`) `ConsolesService`, matching `openShellAt`'s shape exactly — the
  issue names that action as the pattern to follow, not the `reveal`/`@Output`
  pattern the Folder item uses (agent, 2026-09-03).
- "Open IDE" is gated inside the same `@if (isLocalHost)` block as "Folder" rather
  than a second, identical `@if` — both are local-only for the same reason (#497:
  nothing to open, or reach, on a remote engine), so one block is simpler than two
  (agent, 2026-09-03).

## Deviations / notes
- Touched `console-tabs.component.css` (`.shell-error` selector widened to
  `.shell-error, .ide-error`) — inside the declared `client/src/app/components/
  console-tabs/**` scope, not a new file outside it.
- `./mvnw -B test`: see the PR's `## Checks run` section for the exact result; any
  failure outside `client/**`/the new tests here is the same pre-existing
  environmental set already tracked for this machine (see task #628's record).
