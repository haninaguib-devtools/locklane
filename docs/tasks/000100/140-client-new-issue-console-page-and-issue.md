# 140 — Client: New-issue console page and issue-list refresh
Issue: #140 · Part of: #138

## Asked
Give the user a visible way to start the discussion: a button on the project's own
page ("New issue (agent)") that opens a console attached to the project-level
session #139 added, reusing the existing terminal component and the
claude/codex/shell agent picker. When the user leaves the console (or returns to
the issue list), the issue list refreshes so a ticket the agent just opened via
`gh` appears without a manual reload — busting the engine's `GhIssueCache` for
that one fetch rather than waiting on its own 30-second poll.

## Done when
- The project's own page shows an entry-point button; clicking it routes to a
  project-level console view that attaches over the existing WebSocket client
  with agent choice claude/codex/shell.
- Detaching/navigating back re-fetches the issue list with fresh (non-stale-cache)
  data; a newly created issue is visible without a hard reload.
- Reattaching to an existing project-level session resumes scrollback like issue
  consoles do.
- `./mvnw -B test` passes, including new Karma specs for the new component/route.

## Explicitly not
- No session lifecycle/PTY changes (that was #139's).

## Decisions made along the way
- **`fresh=true` on `GET /api/projects/{id}/issues/tree`** (`IssueController`,
  `engine/src/main/java/dev/locklane/engine/github/`): forces a synchronous
  `GhIssueCache.refresh()` before serving, bypassing whatever the scheduled 30s
  poll (`ProjectGhResources`) is still holding. Landed on `tree()` rather than the
  flat `list()` endpoint the issue's example named, since the sidenav — the actual
  issue list a user watches — reads `tree()`, not `list()`; `list()` has no client
  caller that would ever want this (haninaguib, 2026-08-27).
- **New `IssuesService.onProjectStale`/`notifyProjectStale`** (a `Subject<number>`
  alongside the existing HTTP calls, same shape as `ConsolesService`'s
  `onOpened`/`onClosed`): the client-side "please bust your cached view of this
  project" signal. `SidenavComponent` — which owns the actual issue list, not the
  project summary page's issue *counts* — subscribes and re-fetches that project's
  tree with `fresh=true`, in place, the same way it already reacts to a pushed
  `issuesChanged` event (#129). `ProjectConsoleComponent` fires it from
  `ngOnDestroy`, so leaving the console page by any route (an explicit "back"
  click, picking a different sidenav item, browser back) triggers the same
  refresh, matching the issue's "leaves the console (or returns to the issue
  list)" wording without needing two separate code paths.
- **New route `projects/:projectId/console`**, rendered by `AppComponent`
  alongside its existing `main-content`/`project-summary` split — distinguished
  from the project-summary route (both carry only a `:projectId`) by checking for
  a literal `console` URL segment, since Angular's `@if`/`@else if` chain does not
  allow an `as` binding inside an `@else if`.
- **New `ProjectConsoleComponent`** (`components/project-console/`): on load,
  `GET`s the project's console session; a 404 (nothing has ever attached) shows
  the agent picker and a "start" button that `POST`s to mint one, otherwise it
  attaches straight to the terminal — `cmd` is passed on every mount regardless
  (harmless on reattach, since the engine ignores it once a session is already
  running), matching how `MainContentComponent` already treats an issue's own
  consoles.
- **Extracted `AgentPickerComponent`** (`components/agent-picker/`) out of
  `ConsoleTabsComponent`'s existing new-console picker: the claude/codex/shell
  buttons are now a small reusable component with `[agent]`/`(agentChange)`,
  used by both `ConsoleTabsComponent` (alongside its own worktree/main "where"
  choice, which stays local to it — that half has no project-console
  equivalent) and the new page, satisfying the issue's explicit "reusing … the
  claude/codex/shell agent picker" rather than duplicating the three buttons.
- **"New issue (agent)" button lives on `ProjectSummaryComponent`** (the page at
  `/projects/:id/issues` with no issue selected — what the issue calls "the
  project issues page"), shown only while `project.status === 'READY'`, since a
  project that hasn't finished cloning has no checkout for the console session to
  run in (`ProjectConsoleService.start` 404s for anything but `READY`).

## Deviations / notes
- None.
