# 449 — Simplify header console widget to one line, sourced from tab title
Issue: #449

## Asked
The header's "open consoles" list shows two lines per entry today: a title and a
separate description line underneath. Collapse each entry to one line. An
issue-tied console keeps its current title format (issue number + title). A
project-level console shows "Project - " followed by whatever text is actually
showing in that console's tab right now — the user's own name for it if they
renamed it, or the tab's default text otherwise — read from the exact same place
the tab bar itself gets its text, so a rename shows up in the widget immediately
and the two never drift apart again.

Separately, fix which consoles the widget shows. Today it narrows to one
project's consoles whenever the current page happens to belong to a project —
even in the ordinary browser window, from just clicking into a project's issue.
The ordinary window should always show every project's consoles, grouped by
project heading, no matter which project's page is open. Only a window popped
out via the sidenav's per-project arrow (a single-project focused window, #286)
should narrow to that one project's consoles.

## Done when
- Each row in `app-console-indicator` renders one line of text; the second,
  currently-separate description line is gone.
- Console rows tied to an issue still render exactly as they do today (e.g.
  `#123 Some issue title`).
- Console rows for a project-level console render `Project - <text>`, where
  `<text>` matches, character for character, whatever the corresponding tab in
  the tab bar is currently showing — verified for both a never-renamed console
  (its default label) and a console the user renamed (its saved custom name).
- The widget and the tab bar consume the same title-computation source
  (`tabText()`/`labelConsoles()`-style helpers in `console-labels.ts`) for the
  project-console case, not two independently maintained label computations.
- The consoles widget shows every project's consoles, grouped by project
  heading, in the ordinary window — including while browsing a specific
  project's pages there.
- The consoles widget narrows to just one project's consoles, ungrouped, only
  inside a popped-out (`focus=1`) project window.
- Manually verified in the running app: rename an open project console's tab
  and the header widget updates to match without a page reload; browsing into a
  project's issue in the ordinary window keeps showing all projects grouped; a
  popped-out project window shows only its own project's consoles.

## Explicitly not
- Adding a rename/edit UI for issue-specific console tabs — they have none
  today, and the issue-console title already matches its tab.
- Changing the header's own "LockLane - {project name}" text (#309) — it stays
  keyed off the current project page as it is today; only the consoles widget's
  narrowing condition changes.

## Decisions made along the way
- Extracted `labelProjectConsoles()` into `console-labels.ts` as the one place
  that computes a project console's auto label (`console`, `console 2 · claude`,
  ...), reused by both `project-console.component.ts`'s tab strip and the
  widget, combined with the existing `tabText()` to read the user's rename —
  this is the "same title-computation source" the issue asks for (haninaguib,
  2026-08-31).
- The widget's project-console fetch switched from `ConsolesService.list()`
  (bare session ids) to `ProjectConsoleService.listOpen()`, which is the same
  endpoint and ordering the project-console page itself uses and is the only
  one that carries `displayName` — needed both for correctness (the widget
  could not read a rename at all before) and so the auto-label index ("console
  2") lines up with the tab bar's own ordering (haninaguib, 2026-08-31).
- Moved `focusMode`/`focusedProjectId` (#286) out of `AppComponent` and into
  `CurrentProjectService` as `focusMode`/`focusedProjectId`, so the consoles
  widget can read the same focused-window state the sidenav already narrows by,
  instead of re-deriving it privately a second time; `AppComponent` now reads
  both from the service (haninaguib, 2026-08-31).
- The widget narrows using the new `CurrentProjectService.focusedProjectId`
  (focus-mode only), while the header's own project-name text keeps reading the
  existing `projectId`/`current` (route-only, regardless of focus) — the two
  diverge on purpose per the issue's Explicitly-not (haninaguib, 2026-08-31).

## Deviations / notes
- none
