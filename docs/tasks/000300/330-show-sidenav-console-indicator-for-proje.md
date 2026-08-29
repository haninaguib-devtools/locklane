# 330 — Show sidenav console indicator for project-level consoles
Issue: #330

## Asked
Opening a console via a project row's own "+" button (project-level, not attached to
any issue) never lights up the sidenav's console-dot indicator for that project, even
though the console is genuinely running. The indicator should recognize project-level
consoles the same way it recognizes issue-attached ones.

## Done when
- Opening a console via a project row's own "+" button (project-level, no issue) causes
  the project's console-dot indicator in the sidenav to appear, the same way it does for
  an issue-attached console.
- Existing issue-attached console indicator behavior is unchanged.
- A test (or equivalent verification) covers a project-level console session id
  (`"<projectId>-console"` / `"<projectId>-console-<suffix>"`) being recognized by the
  indicator logic.

## Explicitly not
- The backend ownership filter in `IssueWorktreeService.allWorktreeIds()`
  (`isVisibleTo`), which hides another user's console entirely — out of scope here, not
  what the user hit.

## Decisions made along the way
- `consoles.service.ts` already exports `isProjectConsoleSessionId` (added for #139/#177
  and already used by `console-indicator.component.ts`), so no change was needed there —
  only `sidenav.component.ts`'s `refreshConsoleIndicators()`/`hasOpenConsoleForProject()`
  were missing the check (haninaguib, 2026-08-29).

## Deviations / notes
- none
