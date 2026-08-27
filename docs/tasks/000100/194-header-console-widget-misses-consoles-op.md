# 194 — Header console widget misses consoles opened from a project's page
Issue: #194

## Asked
When someone opens a new console from a project's page (the "+" button in the
sidenav, which starts a project-level console via `ProjectConsoleService`), the
"consoles" widget in the app header (`ConsoleIndicatorComponent`) never shows it —
not on open, not after any amount of waiting, not even after switching pages.
Three compounding gaps: (1) the sidenav's console-creation handler never notifies
the widget that something changed; (2) the backend endpoint the widget reads from
(`ConsolesController` -> `IssueWorktreeService.allWorktreeIds`) is deliberately
scoped to only return per-issue consoles, excluding project-level console ids by
design; (3) even if the first two were fixed, the widget's client-side id-parsing
regex would not recognize a project-console's id shape and would drop the entry.
The widget should reflect project consoles the same way it already reflects issue
consoles.

## Done when
- Opening a console from a project's sidenav "+" button causes the header
  consoles widget to include it, in the same browser tab, without a manual page
  refresh.
- The widget correctly displays project-level console entries (title/link), not
  just issue-level ones.
- Closing a project console removes it from the widget the same way.

## Explicitly not
Making the widget update live across *other* open browser tabs/sessions when a
console opens or closes elsewhere — that is a separate mechanism (server-pushed
events vs. an in-tab signal), tracked in #195.

## Decisions made along the way
- Extend Allowed paths beyond the issue's stated Scope to include
  `client/src/app/components/project-console/project-console.component.ts` (hani,
  2026-08-27): that file's `closeConsole()` is the only place a project console is
  ever closed, so the third Done-when criterion (closing removes it from the
  widget) cannot be met without it calling `consolesService.notifyClosed()`. Its
  own `start()` (the project-console page's own "+" and tab-strip "+") also gets
  `notifyOpened()`, for the same reason `notifyOpened()` is added to the sidenav's
  `openNewConsole()` — any way a project console is opened should surface in the
  widget, not just the sidenav's entry point. Approved by the human before editing.

## Deviations / notes
- Existing spec `IssueWorktreeServiceTest.projectConsoleIdsNeverReadAsAnIssuesSession`
  asserted `allWorktreeIds` excluded project-console ids — that was the very
  behavior this task changes (gap 2 in Asked), so it was split: the
  "never an issue's session" half stays, and a new
  `allWorktreeIdsIncludesTheProjectsOwnConsoles` test covers the new inclusion.
- Checks run: `./mvnw -B test` (with `CHROME_BIN` set to a downloaded
  chrome-headless-shell, see [[client-tests-need-chrome-bin]]) — 313 client specs,
  312 engine tests, all green. `./scripts/consistency-check.sh` — passed.
  `bash scripts/protected-paths.sh --stdin` over the diff's changed paths — exit 1
  (no protected surface touched), confirming no plan was required.
