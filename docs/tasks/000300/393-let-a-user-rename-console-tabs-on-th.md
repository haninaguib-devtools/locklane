# 393 — Let a user rename console tabs on the project console page
Issue: #393

## Asked
On the project console page, a user can give an open console tab their own name
instead of the auto-generated one ("console", "console 2 · claude"). Double-clicking
a tab turns its label into an editable field; the name is saved on the server against
that console session, so it comes back after a reload and shows the same in any
browser. Clearing the name restores the auto-generated label. Only the project-level
consoles page gets this; issue-page console tabs keep their auto labels for now.

## Done when
- A tab on `/projects/<id>/console` can be renamed in place, and the new name is shown
  in the tab strip immediately.
- The name survives a page reload and is visible in a second browser session
  (persisted by the engine, not local storage).
- Clearing the name falls back to the auto-generated label.
- A rename is length-bounded and rendered as text (no markup interpretation); an
  over-long name is rejected or truncated rather than breaking the strip.
- The engine exposes an endpoint to set/clear a console session's display name, with a
  Flyway migration adding the column; unauthorized callers cannot rename another
  owner's session.
- `./mvnw -B test` passes, including new engine tests for the endpoint and client tests
  for the rename interaction.

## Explicitly not
- Renaming console tabs on the issue consoles page — the same shared tab-strip
  component is used there, but renaming is enabled only on the project console page.
- Renaming past/closed conversations in the "past sessions" list.
- Any change to how auto labels are generated when no custom name is set.

## Decisions made along the way
- The display name lives on `worktree_sessions` (a new `display_name` column added by
  Flyway migration V11), not a table of its own: it is one nullable attribute of the
  session record the console list already reads, so listing tabs stays a single query.
- Rename is `PUT /api/projects/{projectId}/console/{sessionId}/name` with a JSON body
  `{"name": ...}`; `null` or blank clears it. The same visibility rule the per-tab
  close already applies (`WorktreeSessionAuthorization`) gates it, so one owner cannot
  rename another's session — an unauthorized or unknown session is a 404, exactly as
  close is.
- Names are trimmed and bounded at 60 characters; an over-long name is rejected with
  400 rather than silently truncated, so the user is told rather than surprised. The
  client enforces the same bound on its input via `maxlength`.
- The tab strip stays a shared component: renaming is opt-in per call site through a
  new `renamable` input, default false, so the issue page is untouched.

## Deviations / notes
- none
