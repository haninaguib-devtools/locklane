# 439 — Fix sidenav "+" creating console under wrong project on fast navigation
Issue: #439

## Asked
When someone clicks the "+" on a project's row in the sidenav to open a new console for
that project, the new console is sometimes created for whichever project's console page
they were already looking at, not the project whose "+" they clicked. This happens when
they're on one project's console page and click "+" for a different project: the new
console gets minted server-side under the old (previously-viewed) project, then silently
vanishes from view when the console page finishes switching to the new project — it keeps
running against the wrong project's worktree, orphaned and invisible to the user.

The root cause is a timing race between two things that both happen as part of the same
navigation triggered by the click: `ProjectConsoleComponent`'s `projectId` input (fed by
`CurrentProjectService`, which only updates on the Angular Router's `NavigationEnd` event)
and its subscription to the `?new=1` query param on the root route (which fires earlier,
during route activation, before `NavigationEnd`). If the console page has already finished
loading the previously-viewed project (`this.loading === false`) when the query-param
callback fires, the existing `pendingNewConsole` guard — added for a related but different
race in #370 — does not catch it, and `startDefault()` posts the new console against the
stale `this.projectId` before the input has been updated to the newly-clicked project.

## Done when
- Clicking a project row's "+" in the sidenav while viewing a *different* project's console
  page always creates the new console under the clicked row's project, never the
  previously-viewed one — verified by hand in the running app by opening project A's
  console page, then clicking "+" on project B's sidenav row, and confirming the new
  console appears under B (and nothing new appears under A).
- A test exercises the case that's missing today per the investigation: the project id
  input and the `?new=1` query param changing together in one navigation, with the console
  page already finished loading beforehand (`this.loading === false`) — this combination
  isn't covered by `project-console.component.spec.ts:449-470` (project id fixed while
  `?new=1` toggles) or `:520-535` (project id changes, no `?new=1`).
- The existing `pendingNewConsole` guard and #370's fix keep working: a "+" click while the
  page is still loading, and a "+" click for the *same* project already open, are
  unaffected.
- `client/src/app` builds and `npm test` passes.
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No change to the sidenav's own click handler or navigation call — it already passes the
  right project id; this is purely about how the console page resolves the target project
  once it gets there.
- No change to the engine or `ProjectConsoleService`'s HTTP contract.
- No retroactive cleanup of any console/worktree already stranded under the wrong project
  by this bug in the past.

## Decisions made along the way
- none

## Deviations / notes
- none
