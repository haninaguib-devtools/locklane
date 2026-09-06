# 733 — List a project's open shell sessions on its project page
Issue: #733

## Asked
A project's own page already lists its open worktrees/consoles, but not its open
**shell** sessions — the standalone terminal a user pops out via the hover-revealed
terminal icon on a console tab (opened in a separate `locklane-shells` browser window).
Because the project page has no idea these exist, a user can close every console they
can see and still get "This project has an open worktree or console — close it before
deleting the project." with no way to tell what is actually still open or how to reach
it. The project page should list a project's open shells the same way it lists
worktrees/consoles, let the user close a specific one inline, and link to it so they can
jump straight into it instead of hunting for the separate Shells window.

## Done when
- The project summary page (`client/src/app/components/project-summary/` and/or
  `worktree-list/`) shows every currently-open shell session belonging to the project
  being viewed, alongside its existing worktree/console listing.
- Each listed shell has a control that closes that specific session (backed by the
  existing `ShellsService.close(projectId, sessionId)` → `DELETE
  /api/projects/{id}/shells/{sessionId}`) and updates the list without a full page
  reload.
- Each listed shell has a link that opens it (same pattern as
  `console-tabs.component.ts`'s `window.open('/shells/' + sessionId, 'locklane-shells')`,
  against the existing `/shells/:id` route).
- The list reacts to a shell opened or closed elsewhere while the page is open, the same
  way the existing console list already does (the engine already broadcasts
  `consolesChanged` for shell open/close, per `shells-window.component.ts`).
- After closing every open worktree, console, *and* shell for a project from this page,
  deleting the project succeeds without needing to visit the separate Shells window.

## Explicitly not
- Changing the delete-project gate itself (`ProjectCheckoutService.delete` /
  `hasAnySessions`) or its error message.
- The separate global Shells window (`/shells`, `shells-window.component.ts`).
- Deduplicating/preventing the "every click mints a new shell, never a reuse" behavior —
  a separate concern.

## Decisions made along the way
- none

## Deviations / notes
- none
