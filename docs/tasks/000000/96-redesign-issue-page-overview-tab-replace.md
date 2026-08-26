# 96 — Redesign issue page: overview tab replaces header icon and state pills
Issue: #96

## Asked
The issue page's header crams the title, a truncated description, a "?" help icon
(popup with record path / checks / branch & PR), and the flow-state pills all into the
top strip. Redesign it so the header keeps only the title and short description, and a
new default "Overview" tab holds the fuller view (full description, record path / branch
& PR as links, checks, and the state pills). The existing terminal/session UI moves under
a second "Console" tab.

## Done when
- `issue-header` renders only the title and short description; the "?" button and
  `details-popup` trigger are removed from it.
- A tab bar with "Overview" (default/selected) and "Console" tabs is present on the issue
  page.
- "Overview" tab shows: full (untruncated) issue description, record path as a link,
  branch & PR as a link, checks status, and the state pills (previously `flow-strip`).
- "Console" tab shows the existing terminal/session UI (`console-tabs` and terminal
  panes), unchanged in behavior.
- `client/src/app` build and existing tests pass.

## Explicitly not
- No change to the underlying data the header/popup/flow-strip consume (IssuesService,
  issue/detail models).
- No new tabs beyond Overview and Console.

## Decisions made along the way
- `details-popup` is deleted rather than left dead: its record/checks/branch content
  moves into a new `overview-tab` component, and nothing else referenced it.
- The record-path and branch/PR links need a GitHub web URL, which `IssueDetail`/`GhIssue`
  don't carry (and the issue's non-goals rule out adding to those models). `Project.gitUrl`
  is already fetched elsewhere in the app (`project-summary`) via `ProjectsService.list()`
  — reused the same pattern in `main-content` to derive the repo's web URL client-side
  (`repo-web-url.ts`), rather than touching the issue/detail models.
- The Console tab's content (console-tabs + terminal panes) is hidden with a CSS class,
  not removed with `@if`, when Overview is active — matching the existing pattern for
  inactive terminal panes, so switching tabs doesn't tear down a live PTY session.

## Deviations / notes
- After the first pass, the human asked for two changes (approved in the moment):
  1. **One merged tab strip, not two.** Instead of a separate "Overview" / "Console"
     tab bar sitting above `console-tabs`, "Overview" is now a permanent, unclosable
     first tab inside `console-tabs` itself — the same strip that lists open consoles
     and the "+" button. `console-tabs` gained the `OVERVIEW_TAB_ID` sentinel
     (`console-labels.ts`) for this; `main-content`'s `activeTab` now holds either that
     sentinel or an open console's id, replacing the old `activeTab: 'overview' |
     'console'` union. The separate "Console" tab is gone.
  2. **Flow-strip moves back out of the Overview tab**, directly below the header —
     "how it used to be" before this task, and now always visible regardless of which
     tab (Overview or a console) is selected, since it's a pipeline-progress indicator,
     not per-tab content. `overview-tab` no longer renders it. Within the Overview tab,
     the record/checks/branch details pane now comes before the full description
     (previously the other way around).
  - Net effect on defaults: landing on an issue still defaults to the Overview tab
    (unchanged), even when a console was remembered as last-active for that issue —
    including via the header's "jump to an open console" picker (#32), which now lands
    on Overview rather than the console's terminal directly, needing one extra click to
    reach it. Not raised by the human's ask; flagging here rather than fixing
    unprompted.
