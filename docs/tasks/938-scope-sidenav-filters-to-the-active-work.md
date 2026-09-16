# 938 — Scope sidenav filters to the active workspace
Issue: #938 · Part of: #934

## Asked
Scope the sidenav filters to the active workspace. The text filter and the "hide shipped" checkbox keep their current behaviour, but while a workspace is active their values are read from and written to that workspace's record in the `WorkspaceStore` (`filterText`, `hideShipped`). Switching workspaces swaps the values; returning to "All projects" restores today's behaviour (in-memory text filter, hide-shipped default). Typing in the filter while in a workspace saves as you type; nothing is saved when no workspace is active.

## Done when
- Specs: activating workspace A shows A's saved filter text and hide-shipped state; changing them updates A in the store; switching to B shows B's; switching to all projects shows an empty filter and the default hide-shipped, and further changes are not written to any workspace.
- A new workspace starts with an empty filter and hide-shipped on.
- `cd client && npm test` passes.

## Explicitly not
- New filter kinds. Remembering filters when no workspace is active.

## Decisions made along the way
- `filterText` and `hideShipped` stay public fields on the sidenav (many existing specs and the template's `ngModel` set them directly) but become getter/setter pairs: the setter writes through to the active workspace via `WorkspaceStore.updateFilters`, and an `effect` on `activeWorkspaceId` loads the values in (writing the private fields, so loading never saves back). With no workspace, or a `ws` naming none, the fields behave exactly as before.
- `WorkspaceStore` needed no change: `create` already starts a workspace with `''` / `true` (#935), which its spec covers.

## Deviations / notes
- `scripts/check.sh` (`./mvnw -B test`): engine tests fail with the known local-environment failures only (identical on the integration branch); the diff is client-only and `cd client && npm test` passes.

## Agents
- work: claude-code / claude-fable-5-1
