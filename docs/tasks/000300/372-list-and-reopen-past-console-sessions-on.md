# 372 — List and reopen past console sessions on the project page
Issue: #372 · Part of: #371

## Asked
Give the project-level console page the same "past conversations" list and reopen
action the issue page's Overview tab already has for issue-scoped consoles: a way to
see a Claude/Codex/OpenCode conversation that ran in a project console that has since
been closed, and open a fresh console that resumes it. The data is already captured
for project consoles (`ResumeIdScanner` writes every console's `(tool, resumeId)` into
`ConsoleResumeSessionRepository` regardless of scope) — nothing queries it by project,
and there is no project-scoped equivalent of `WorktreeCreationService.reopenSession`.

## Done when
- A project-scoped resume-sessions query (mirroring `resumeSessionsForIssue`) returns
  every conversation captured in that project's console family, newest sighting first,
  visible under the same project-owner/admin rule the rest of `ProjectConsoleService`
  uses, and excluding the legacy shared-checkout-shaped ids.
- A new endpoint exposes it (`GET /api/projects/{projectId}/console/resume-sessions`),
  returning `tool`, `resumeId` and `capturedAt` per session, mirroring
  `WorktreeController.ResumeSessionView`.
- A project-scoped reopen path (mirroring `WorktreeCreationService.reopenSession`)
  mints a new project-console session that reuses the original conversation's working
  directory, exposed via a new endpoint mirroring `POST /resume-sessions/reopen`.
- `ProjectConsoleComponent` renders the existing `SessionListComponent` for this
  project's past sessions and wires its `reopen` output to the new endpoint, threading
  `tool`/`resumeId` into the terminal launch the same way `main-content.component.ts`
  already does for issues.
- Manual check: start a project console, have a short Claude (or Codex/OpenCode)
  conversation in it, close the tab, reload the project console page, and reopen that
  conversation from the list — the new console resumes the same conversation history.
- `./mvnw -B test` and the client's test suite both pass.

## Explicitly not
- Showing each session's real conversation title instead of a captured timestamp —
  split to #373.

## Decisions made along the way
- The project's resume-sessions endpoint returns `WorktreeController.ResumeSessionView`
  itself rather than a parallel project-side row type, so the client keeps one
  `ResumeSession` model and one `SessionListComponent` for both lists (haninaguib,
  2026-08-29).
- A reopened console's id is `<projectId>-console-<originalSuffix>-resume-<8-hex>`.
  Unlike an issue, a project console has no stable fallback directory: its session
  record and (since #339) its worktree are both destroyed when its tab closes, which
  is precisely the state a past conversation is reopened from. Carrying the original
  console's suffix ahead of the resume tail keeps the directory
  (`<repoName>-console-<originalSuffix>`) derivable from the id alone, and stripping
  any resume tails when deriving means reopening an already-reopened console still
  lands in the one directory the conversation lives in, rather than a path nothing
  ever created (haninaguib, 2026-08-29).
- A conversation's directory that no longer exists is recreated as a fresh detached
  worktree at that same path rather than the reopen being refused — after #339 that is
  the ordinary case, and Claude/OpenCode key a stored conversation by working
  directory, so restoring the path is exactly what makes `--resume` find it again
  (haninaguib, 2026-08-29).
- The legacy bare `<projectId>-console` id is excluded from both the listing and the
  reopen path — read as the issue's "legacy main-checkout-shaped ids" for the project
  console family: that id was only minted before #177 and so only ever ran in the
  project's own shared checkout, which #341 retired as a console location, making any
  reopen of it a dead end (haninaguib, 2026-08-29).
- Visibility is decided straight from the console id through
  `WorktreeSessionAuthorization` (which resolves the owning project from the id's own
  numeric prefix), not through a session-record lookup that falls back to "visible"
  when the record is gone. A closed console is the normal case here, so that fallback
  would have made every closed console's conversation ids readable by any
  authenticated user (haninaguib, 2026-08-29).
- The list sits in a collapsed "past sessions (n)" disclosure under the page header
  rather than in a rail or the empty state: #256 auto-starts a console the moment the
  page loads with none open, so there is no empty state to host it, and the terminal
  keeps its full height until the list is actually asked for (haninaguib, 2026-08-29).

## Deviations / notes
- `ResumeSession.tool` widened from `'claude' | 'codex'` to include `'opencode'`.
  `client/src/app/models/` is inside this task's scope, and the engine has captured
  OpenCode resume ids since #295 — the project list surfaces them, so the old union
  was wrong about data this task puts on screen.
- `app.component.spec.ts` and `project-console.component.spec.ts` drain the new
  `/console/resume-sessions` request in `afterEach` rather than asserting it in each
  of the ~20 tests that merely mount the page. This mirrors the `/api/usage` drain
  `app.component.spec.ts` already does for the same reason.
- Known limitation, not fixable from the id alone: a conversation captured in a
  project console created between #177 and #314 ran in the project's *shared*
  checkout, and once its session record is gone nothing distinguishes it by id shape
  from a post-#314 one. Reopening such a conversation creates an empty worktree at the
  derived path, where the CLI simply reports it cannot resume. Only the pre-#177 bare
  id is excludable by shape, and it is excluded.
- Not verified in this session: the manual browser check in the issue's Done-when
  (real conversation, close the tab, reload, reopen). Everything below it is covered by
  automated tests; that step needs a human at a running app.

## Proposed follow-up (not opened — AGENTS.md §Conventions)
- `IssueWorktreeService.resumeSessionsForIssue` still resolves a console's visibility
  by looking up its session record and treating "no record" as visible to everyone.
  Since #242 the owning project is resolvable from the console id itself, so that
  fallback is no longer necessary — and a closed console (the case the list exists
  for) has no record, so an issue's past conversation ids are currently listed to any
  authenticated user, not only the project's owner. Worth its own issue under the same
  area as this one.
