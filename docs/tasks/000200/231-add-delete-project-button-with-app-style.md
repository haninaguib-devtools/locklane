# 231 — Add delete-project button with app-styled confirm dialog
Issue: #231

## Asked
On the project's own page (`project-summary.component`), add a button that deletes the
project. Before deleting, the app must confirm the project has no open worktrees or
consoles and refuse the delete (with a clear message) if it does. Deleting must ask the
user to confirm first, using a new app-styled confirm dialog component rather than the
browser's native `confirm()`. The same new dialog component replaces the two existing
`confirm()` calls in the client: closing a console tab (`console-tabs.component.ts`
`closeTab`) and the existing project-delete affordance already in the sidenav
(`sidenav.component.ts` `deleteProject`), so every place that asks "are you sure" in the
app looks and behaves the same way.

## Done when
- `project-summary.component` has a delete-project button/action.
- Clicking it opens the new app-styled confirm dialog (not `window.confirm`); confirming
  calls `ProjectsService.delete(id)`; cancelling does nothing.
- The backend refuses to delete a project that has any open worktree or console session
  for it (a non-2xx response, e.g. 409), instead of the current unconditional delete in
  `ProjectCheckoutService.delete`. `ProjectController`'s `DELETE /api/projects/{id}`
  surfaces that refusal to the client.
- The client shows the refusal to the user (e.g. inline error/toast) instead of silently
  failing or navigating away.
- `console-tabs.component.ts`'s `closeTab` uses the same new confirm dialog component
  instead of `confirm(...)`.
- `sidenav.component.ts`'s `deleteProject` uses the same new confirm dialog component
  instead of `confirm(...)`.
- `./mvnw -B test` and the client's existing test suite pass; new/updated tests cover the
  backend refusal-when-open-sessions-exist behavior and the new dialog component.

## Explicitly not
Redesigning the settings-dialog/add-project-popup backdrop pattern itself — the new
confirm dialog follows that existing visual pattern rather than introducing a new one.
No change to what happens when a worktree/console is closed, only to how that action is
confirmed.

## Decisions made along the way
- The refusal check lives in `IssueWorktreeService.hasAnySessions(projectId)`
  (haninaguib, 2026-08-27): checks every worktree/console session for the project
  regardless of owner, since deleting a project out from under *anyone's* open session
  (not just the requesting user's) would orphan it — the existing per-user visibility
  filters in this class exist for what a user is *shown*, not for a safety gate.
- `ProjectCheckoutService.delete` returns a `DeleteOutcome` enum (`NOT_FOUND`,
  `HAS_OPEN_SESSIONS`, `DELETED`) rather than a boolean, so `ProjectController` can map
  the new refusal case to 409 without a second query (haninaguib, 2026-08-27).
- On a successful delete, `project-summary.component` navigates to `/` (the workspace
  Overview) since the project it was showing no longer exists (haninaguib, 2026-08-27).

## Deviations / notes
- `client/src/app/components/project-console/project-console.component.spec.ts` was
  updated (haninaguib, 2026-08-27): it exercises `console-tabs`' close flow end-to-end
  and its three close-related specs assumed the old synchronous `window.confirm()`. Not
  a scope change — required so those specs keep testing the same behavior through the
  new two-step (open dialog, click confirm) flow.
- Checks run: `./mvnw -B test` with `CHROME_BIN` set to a downloaded
  chrome-headless-shell (see memory `client-tests-need-chrome-bin`) — 364 client specs,
  324 engine tests, all green. `./scripts/consistency-check.sh` — passed.
  `git status --porcelain | awk '{print $2}' | bash scripts/protected-paths.sh --stdin`
  over the full diff (tracked + untracked) — exit 1 (no protected surface touched),
  confirming no plan was required.
