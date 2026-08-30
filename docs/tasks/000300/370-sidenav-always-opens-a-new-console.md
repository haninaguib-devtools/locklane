# 370 — Sidenav "+" always opens a new console
Issue: #370

## Asked
The "+" button on a project's row in the sidenav is meant to be the one-click way to
open a brand-new console for that project, but today it usually lands the user in an
existing one. It posted a new console session, then navigated to
`/projects/<id>/console?session=<newId>`; that page lists the project's open consoles
from the engine, and the engine only counts a console as "open" once it has been
attached to at least once. The freshly minted id had never been attached, so it was
missing from the list, the page discarded the `session` query param as unknown, and it
selected the most-recently-attached existing console instead. Only when the project had
no open consoles did the page's empty-state path mint one of its own and show it — which
is why the button appeared to work exactly then and never otherwise. The
minted-but-abandoned session was not free either: `ProjectConsoleService.start` creates a
fresh detached git worktree on disk for every "+" click, and one that is never attached is
left behind. After this task, clicking "+" always opens and displays a new console for
that project, and does not strand a worktree.

## Done when
- Clicking a project row's "+" in the sidenav opens a new console for that project and
  lands on `/projects/<id>/console` with that new console's tab selected, whether or not
  the project already has open consoles.
- One "+" click produces exactly one new console session, and therefore exactly one new
  console worktree — no session is minted and then abandoned.
- The project's existing consoles are untouched: they stay open and stay in the tab strip,
  with the new console added alongside them.
- The double-click guard still holds: further "+" clicks are ignored while one console is
  being opened, and a failure re-arms the button.
- `client/src/app` builds and `npm test` passes, with a sidenav test covering the case that
  is broken today (a project that already has open consoles still gets a new one).
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No change to the engine: `ProjectConsoleService`/`ProjectConsoleController` keep their
  current contract, including the rule that `listOpen` reports only consoles attached at
  least once.
- No cleanup of console worktrees already stranded on disk by past "+" clicks.
- No change to the console page's own tab mechanics, its agent defaulting, or the
  per-project consoles button (the dot, #312) next to the "+".

## Decisions made along the way
- **The minting moves to the console page; the sidenav only asks** (Claude, 2026-08-29).
  Two handoffs were possible. Keeping the mint in the sidenav means the page has to
  display a session the engine will not list, and the page needs that session's
  `workingDirectory` to attach a terminal — which only the sidenav holds, and which would
  then have to travel through the URL or through router history state. Both survive an SPA
  navigation badly and neither survives a reload, so a dropped handoff would still strand
  a worktree. Handing over the *request* instead (`?new`) means the page mints the session
  it is about to show, with the engine's own `workingDirectory` in hand, and nothing can
  be minted and then lost.
- **The request rides in a query param, read live rather than from the snapshot** (Claude,
  2026-08-29). Routing is component-less and `ProjectConsoleComponent` reloads only when
  its `projectId` input changes, so a "+" click while that project's console page is
  already showing changed nothing at all — a second facet of the same bug. Subscribing to
  `queryParamMap` is what makes that case work.
- **The page drops `new` from the URL as soon as it acts on it** (Claude, 2026-08-29), so
  a reload is not a second mint, and so the next "+" click is a real query-param change
  rather than a same-URL navigation the router ignores. Other params (`focus` from #286,
  `session` from #179) are preserved.

## Deviations / notes
- **The double-click guard moved from the sidenav button to the console page.** The
  observable contract in Done when is unchanged — a "+" click while a console is being
  opened is ignored, and a failed start re-arms — but it is now enforced by the page's
  existing `starting` flag rather than the sidenav's `startingConsoleFor`. With the mint
  gone from the sidenav there is nothing there to wait on and nothing that can fail, so
  `startingConsoleFor`, `isStartingConsole()` and the button's `[disabled]` binding were
  removed as unreachable state rather than left in place always-false. Its three sidenav
  specs moved to the console page's spec along with the behaviour.
- `ProjectConsoleService`, `AgentStore` and `DefaultAgentStore` are no longer injected by
  the sidenav — the page already applies the Settings default agent to every console it
  starts (#219/#256), which is the same source the "+" used.
- A `?new` request that arrives while the open-console list is still in flight is queued
  (`pendingNewConsole`) and started once that list lands; starting immediately would let
  the list's response overwrite the console just added. That flag deliberately survives
  `load()`'s state reset: clicking "+" for a *different* project changes the `projectId`
  input and the query params in the same navigation, in no guaranteed order.
- A `?new` request is honoured even when listing the open consoles fails, rather than
  dropping the click; a failed start then shows the page's ordinary start error.
