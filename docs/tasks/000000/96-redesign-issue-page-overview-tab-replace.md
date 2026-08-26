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
- none
