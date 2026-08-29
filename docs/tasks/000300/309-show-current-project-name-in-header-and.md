# 309 — Show current project name in header and scope consoles widget to it
Issue: #309

## Asked
When a browser window/tab has a project open (the route carries a `projectId`), the
header should read "LockLane - {project name}" instead of the static "LockLane", so
someone with several windows open — one per project — can tell them apart at a glance.
In the same window, the consoles widget in the header should show only console sessions
belonging to that open project, rather than every project's sessions as it does today
(#301). A window with no project selected keeps today's plain header and all-projects
widget behavior.

## Done when
- The header text reads "LockLane - {project name}" whenever the active route carries a
  `projectId`, and plain "LockLane" when it does not.
- The consoles widget, when the window's route carries a `projectId`, lists only that
  project's console entries; when the route carries no `projectId`, it keeps today's
  all-projects grouped behavior (#301) unchanged.
- A reusable way to read "the project open in this window" (id and name) exists and is
  used by both the header and the widget, rather than each re-deriving it from the route
  independently.
- Client unit tests cover: header text with and without a selected project; consoles
  widget filtered to one project vs. showing all projects.
- Existing consoles-widget tests (including #301's all-projects grouping coverage)
  continue to pass, updated if their scenario now implies a project is selected.

## Explicitly not
- What the consoles widget fetches over the network (still fetch all projects and filter
  client-side, or fetch only the current project) — an implementation choice.
- Browser tab/document-title text beyond the in-page header.
- Project ownership/authorization semantics (#239, #242).

## Decisions made along the way
- Added `CurrentProjectService` (`client/src/app/services/current-project.service.ts`),
  providedIn root, as the shared "project open in this window" accessor: derives
  `projectId` from the route the same way `AppComponent` used to privately, fetches the
  project list once, and exposes `projects` (the full list) and `current` (the matched
  `{id, name}` or `null`). `AppComponent.selectedProjectId` now delegates to it instead
  of deriving its own route signal, and `ConsoleIndicatorComponent` reuses its `projects`
  signal instead of fetching its own project list — one `/api/projects` call backs both
  the header and the widget instead of two. (haninaguib, 2026-08-29)
- `ConsoleIndicatorComponent` narrows to the current project by filtering the shared
  project list down to a `visibleProjects` signal (all projects when no project is
  selected, just the current one otherwise); `entries`/`groups`/`showGroupHeadings` all
  derive from it, so scoping to one project also means no group heading ever shows for
  it, without a separate code path. (haninaguib, 2026-08-29)

## Deviations / notes
- none
