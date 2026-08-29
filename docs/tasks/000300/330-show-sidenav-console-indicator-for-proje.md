# 330 — Show sidenav console indicator for project-level consoles
Issue: #330

## Asked
Opening a console via a project row's own "+" button (project-level, not attached to
any issue) never lights up the sidenav's console-dot indicator for that project, even
though the console is genuinely running. The indicator should recognize project-level
consoles the same way it recognizes issue-attached ones.

## Done when
- Opening a console via a project row's own "+" button (project-level, no issue) causes
  the project's console-dot indicator in the sidenav to appear.
- Opening only an issue-attached console (no project-level console open) does **not**
  light the project's console-dot indicator — the project dot tracks project-level
  consoles exclusively; it does not aggregate issue-attached ones.
- The issue row's own console-dot indicator (per-issue) is unchanged.
- A test (or equivalent verification) covers: (a) a project-level console session id
  (`"<projectId>-console"` / `"<projectId>-console-<suffix>"`) being recognized by the
  indicator logic, and (b) an issue-attached session id alone not causing
  `hasOpenConsoleForProject()` to return true.

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
- Scope corrected in triage (haninaguib, 2026-08-29): the first implementation had
  `hasOpenConsoleForProject()` OR project-level state together with the pre-existing
  `anyKeyForProject(openConsoleIssues, projectId)` aggregation from #312, so the project
  dot still lit up for any issue-attached console under the project, not just
  project-level ones. That aggregation is now explicitly out of scope for this
  indicator — confirmed with the user, who observed the dot lighting up for an
  issue-attached console and clarified the project dot must track project-level
  consoles only. `anyKeyForProject(openConsoleIssues, projectId)` is being removed from
  `hasOpenConsoleForProject()` (it stays in `hasAttentionWaitingForProject()`, a
  separate concern). Issue #330's body is unchanged (`docs/tasks/README.md`: intent
  changes after work starts land here, not in the issue body).
