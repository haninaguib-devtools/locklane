# 937 — Narrow sidenav, overview and agents widget to the active workspace
Issue: #937 · Part of: #934

## Asked
Make the active workspace actually narrow what the app shows. When `CurrentProjectService.visibleProjectIds` is non-null: the sidenav lists only those projects (reusing the mechanism that already narrows it to one project in `focus=1` mode, so both narrowings compose); the Overview lists only those projects; the open-agents header widget counts and lists only agent sessions belonging to those projects, the way it already narrows to one project in a focused window. A page for a project outside the workspace (a pasted link, a notification click) still loads and renders normally — only the lists are narrowed, the workspace is never dropped. With no workspace everything behaves exactly as today.

## Done when
- Specs in sidenav, overview and agent-session-indicator each show: null `visibleProjectIds` → all projects; a set → only those; the focused-window narrowing still works on top.
- A spec shows navigating to `projects/<id>/issues` for a project outside the workspace renders the project page with `ws` still in the URL.
- Project created while in a workspace: it is not added automatically and does not appear until the workspace is edited (spec).
- `cd client && npm test` passes.

## Explicitly not
- Header dropdown UI (previous child).
- Per-workspace filters (next child).

## Decisions made along the way
- The sidenav and overview read `visibleProjectIds` straight off the shared `CurrentProjectService` (injected) rather than through a new `@Input` bound from `app.component.html`, which is outside this task's scope; `app.component.ts` therefore needed no change. Both re-narrow via an `effect` on the signal (first run skipped, `ngOnInit` covers it), the same reload a `focus=1` change already runs.
- Both narrowings compose in one place per component: sidenav `isListed()` (also what `couldList()` reads, so a `projectCreated` event for a project outside the workspace triggers no reload), overview's list `map`, and the agents widget's `visibleProjects$`.
- The agents widget builds the workspace's ids as a synchronous observable (`activeWorkspaceId$` + `toObservable(store.workspaces)` started with `null`), keeping its existing synchronous-stream contract while still reflecting an edit to the active workspace without a navigation.
- The sidenav and overview specs stub `CurrentProjectService` with a writable `visibleProjectIds` signal: the real service fetches `/api/projects` itself and would double every existing project-list expectation.

## Deviations / notes
- "Navigating to `projects/<id>/issues` for a project outside the workspace renders the project page with `ws` still in the URL" is covered from the agents-widget spec (real routes: the URL keeps `ws`, the widget stays narrowed) and the sidenav spec (`selectedProject` outside the workspace, list narrowed), not from `app.component.spec.ts`, which is outside this task's scope.
- `scripts/check.sh` (`./mvnw -B test`): engine tests fail with the known local-environment failures only (identical on the integration branch); the diff is client-only and `cd client && npm test` passes.

## Agents
- work: claude-code / claude-fable-5-1
