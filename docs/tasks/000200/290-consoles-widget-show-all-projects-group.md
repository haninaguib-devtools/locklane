# 290 — Consoles widget: show all projects, group dialog by project
Issue: #290

## Asked
The header consoles widget (`ConsoleIndicatorComponent`) only renders when a project is
currently selected, and only lists that one project's consoles. It should instead always
be visible and reflect consoles open across every project the user has, so a console left
open in a project you've navigated away from still shows up. Its picker dialog currently
lists a flat, ungrouped array of entries for the single project; it should group entries
under a heading per project, except when the user has exactly one project, where a lone
group heading would be redundant and should be omitted.

## Done when
- The header consoles widget renders whenever the user is logged in and has at least one
  project, independent of whether a project is currently selected in the route.
- Its badge/count reflects consoles open across every project, not just the selected one.
- The picker dialog groups entries by project, each group headed by that project's name;
  when the user has exactly one project, the dialog renders as it does today with no
  group headings.
- Selecting an entry navigates to that entry's own project (not necessarily whichever
  project is currently selected).
- Existing behaviors keep working across the now-multi-project entry set: issue-scoped
  vs. project-level console labeling, the "waiting for attention" highlight, live updates
  when a console opens or closes anywhere, arrow/enter/escape keyboard nav, and the
  single-entry direct-link shortcut (now applied against the total count across all
  projects).
- `client/src/app` build and the `console-indicator` / `app.component` test suites pass.

## Explicitly not
No backend/API changes — `ProjectsService.list()` already returns every project, and
`sidenav.component.ts`'s `refreshConsoleIndicators()` already demonstrates fanning the
existing per-project `ConsolesService.list(projectId)` call out across all projects with
`forkJoin`; this task follows that same client-side pattern rather than adding a new
all-projects endpoint.

## Decisions made along the way
- Group-heading visibility is driven by the user's total project count
  (`ProjectsService.list()` length > 1), not by how many of those projects currently have
  an open console — so headings don't flicker in and out as consoles open/close while the
  project count stays fixed (haninaguib, 2026-08-28).
- The project list is fetched once per mount, fed through a `ReplaySubject` the
  single-project version used to feed `projectId$` off `ngOnChanges` — entries alone
  re-fetch on `onOpened`/`onClosed`, not the project list itself, matching how
  `sidenav.component.ts` also never re-fetches its own project list on those events
  (haninaguib, 2026-08-28).

## Deviations / notes
- none
