# 85 — Make the sidebar project name prominent and give a project its own summary page
Issue: #85

## Asked
In the sidebar, a project's name currently reads like a small grey caption sitting further
in from the left than the issues underneath it, so the thing that owns everything below it
looks less important than its contents. Make the project name the strongest, left-most
heading in its section — at least as prominent and as far left as an initiative row — and
make it clickable. Clicking it opens the project's own page in the main area: a summary of
the project showing how many issues it has and the other facts about it a person would want
at a glance (repository, branch, clone status, when it was added). Today clicking a project
name only collapses its section, and with no issue selected the main area just says "select
an issue to begin".

## Done when
- The project name in the sidebar renders at an indentation less than or equal to that of a
  top-level initiative row, and at a font weight/size at least that of an initiative title.
- Clicking a READY project's name in the sidebar navigates to that project's own view; the
  collapse/expand twisty still collapses the section without navigating.
- With a project selected and no issue selected, the main area shows the project summary
  instead of the "select an issue to begin" placeholder.
- The summary shows, at minimum: total issue count, and the split of open vs closed and
  initiative vs task; plus project name, git URL, default branch, status, and created date.
- Counts are derived from data the client already loads (`IssuesService.tree`) — no new
  engine endpoint in this task.
- The selected project's row is visibly marked as selected when its summary is showing.
- `./mvnw -B test` passes, including new Angular specs covering the summary component's
  counts and the sidenav name click.
- `./scripts/consistency-check.sh` passes.

## Explicitly not
- No new engine/API endpoint; counts come from the tree the sidenav already fetches.
- No project editing, renaming, or settings on the summary page — display only.
- No change to how issues themselves are rendered or selected.
- Does not touch authentication, the terminal, or the console panes.

## Decisions made along the way
- The project summary reuses the existing `projects/:projectId/issues` route rather than
  adding a `projects/:projectId` one (Claude, 2026-08-26). That route already means
  "project chosen, no issue chosen" — it is where `defaultProjectRedirect` lands and where
  `AppComponent` showed "select an issue to begin". Giving it real content is the whole
  change; a second URL for the same view would only add a redirect to maintain. `app.routes.ts`
  is therefore untouched, and its comment about the empty state is corrected in place.
- `ProjectSummaryComponent` fetches its own `ProjectsService.list()` and
  `IssuesService.tree(projectId)` rather than receiving them from `SidenavComponent`
  (Claude, 2026-08-26). It matches how `MainContentComponent` loads its own issue data, and
  keeps the sidenav free of a second consumer of its private section state. There is no
  `GET /api/projects/{id}`, and adding one is a non-goal, so the project is picked out of
  the list response.
- Clicking the project name selects the project; collapsing moved onto the twisty button
  (Claude, 2026-08-26). Previously the whole header row toggled the fold. One row cannot do
  both, and the twisty is the affordance that already means "fold" everywhere else in this
  sidenav (initiative rows).

## Deviations / notes
- none
