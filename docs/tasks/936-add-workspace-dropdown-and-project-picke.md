# 936 — Add workspace dropdown and project picker to the header
Issue: #936 · Part of: #934

## Asked
Add the workspace dropdown to the top header, next to the open-agents widget, backed by the `WorkspaceStore` and `ws` query param. The dropdown lists **All projects** (top item; navigates with `ws` removed), one row per workspace (navigates with `ws=<id>`), a separator, and **New workspace…** which asks for a name, opens the project picker, and switches to the new workspace. The active workspace row exposes **Edit projects**, **Rename**, **Open in new window** (`window.open` of the current URL with `ws=<id>` set, the same way the sidenav opens a `focus=1` window), and **Delete** (confirm dialog; deleting the active workspace navigates back to all projects). The project picker is a small dialog with one checkbox per project, reading the project list already shared through `CurrentProjectService`. Follow the existing modal conventions (scrim, focus trap, arrow/enter/escape) used by the agents widget.

## Done when
- The header shows the dropdown, with the active workspace's name (or "All projects") as its label.
- Specs cover: selecting a workspace sets `ws` in the URL; "All projects" removes it; "New workspace…" creates, picks projects and activates; delete of the active workspace lands on all projects; "Open in new window" calls `window.open` with `ws=<id>`.
- Keyboard: Escape closes, arrows move, Enter selects, matching the agents widget.
- `cd client && npm test` passes.

## Explicitly not
- Narrowing the sidenav, overview or agents widget to the workspace (next child).
- Per-workspace filters (last child).

## Decisions made along the way
- One dialog component (`workspace-projects-dialog`) serves create (name + checkboxes), edit projects (checkboxes only) and rename (name only), via `showName`/`showProjects` inputs -- "New workspace…" asks for the name and the projects in that one dialog rather than two.
- Switching workspaces navigates with `[]` + `queryParamsHandling: 'merge'` and `ws` named explicitly, so the page stays put and only `ws` changes; `ws: null` removes it.
- The dropdown is hidden inside a `focus=1` single-project window, alongside the existing "+ add project" button: a pop-out already narrows to one project, so a workspace has nothing to narrow there.
- Delete uses the shared `app-confirm-dialog`; cancelling leaves the workspace and the URL untouched.

## Deviations / notes
- `scripts/check.sh` (`./mvnw -B test`): engine tests fail with the known local-environment failures only (identical on the integration branch); the diff is client-only and `cd client && npm test` passes (1003 specs).

## Agents
- work: claude-code / claude-fable-5-1
