# 935 — Add WorkspaceStore and ws query param
Issue: #935 · Part of: #934

## Asked
Introduce the workspace model and make the active workspace part of the URL. A `WorkspaceStore` (client-only, localStorage, same pattern as `pin-store.ts` / `collapse-store.ts`) holds a list of workspaces `{ id, name, projectIds, filterText, hideShipped }` with create, rename, delete, set-projects and update-filters operations, exposed as signals/observables. `CurrentProjectService` gains `activeWorkspaceId` derived from the `ws` query param (null when absent = "all projects") and a computed `visibleProjectIds` (null when no workspace; otherwise the workspace's project ids). `FocusPreservingRouter` preserves `ws` on every in-app navigation exactly as it preserves `focus=1`. A `ws` value that names no stored workspace behaves like no workspace. Nothing in the UI uses this yet.

## Done when
- `client/src/app/services/workspace-store.ts` exists with spec coverage for create/rename/delete/set-projects/update-filters and for reload from localStorage.
- `CurrentProjectService` exposes `activeWorkspaceId` and `visibleProjectIds`, with specs for: no param → null; valid id → that workspace's project ids; unknown id → null.
- A spec shows `FocusPreservingRouter` keeps `ws=<id>` across `navigate` / `navigateByUrl`, alongside `focus=1`.
- `cd client && npm test` passes.

## Explicitly not
- No UI: no dropdown, no dialogs, no narrowing of the sidenav (later children).

## Decisions made along the way
- `WorkspaceStore` exposes the list as a readonly signal (`workspaces`) plus plain `list()`/`get()`; `visibleProjectIds` is a `computed` over it, so editing a workspace's projects is reflected without a navigation.
- Workspace ids are `crypto.randomUUID()` strings. Reload tolerates entries written before `filterText`/`hideShipped` existed by filling the defaults (`''`, `true`).
- `FocusPreservingRouter` carries `ws` exactly like `focus`: only when the current URL has it and the caller did not name `ws` itself (`ws: null` drops it, `ws: '<other>'` switches).

## Deviations / notes
- `scripts/check.sh` ran `./mvnw -B test`: engine tests fail with the known local-environment failures (worktree/credential-helper/`/bin/true` tests, identical on the integration branch); the diff is client-only and `cd client && npm test` passes (996 specs). Reported as FAIL in the PR with this note.

## Agents
- work: claude-code / claude-fable-5-1
