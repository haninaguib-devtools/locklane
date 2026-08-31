# 460 — Close shell sessions: close endpoint, owner-gated mint, authenticated shell paths
Issue: #460 · Part of: #444

## Asked
The engine can mint and list shell-kind console sessions (#445), but nothing can
end one: both existing session-close endpoints are family-gated and refuse
shell-shaped ids, so a shell row persists until its owner's account is
cascade-deleted — and any row makes `IssueWorktreeService.hasAnySessions` true,
refusing the project's delete indefinitely. The Shells window (#446) requires
closing a shell "like closing a console tab elsewhere", which is impossible until
this capability exists. Complete the shell session lifecycle on the engine, and
fold in the two hardening items the independent reviews of PRs #457 and #458
flagged: mint refuses a caller who does not own the project, and the shell REST
paths are authenticated by SecurityConfig matchers rather than falling through to
`anyRequest().permitAll()`.

## Done when
- A close endpoint (`DELETE /api/projects/{projectId}/shells/{sessionId}`) ends a
  shell session for good — kills any live PTY and deletes the persisted row via
  `SessionRegistry.close` — and returns 404 for an id outside this project's
  shell family, one never persisted, or one not visible to the caller (the same
  project-owner gate the other close endpoints apply). It never touches any
  directory on disk.
- Closing an open shell broadcasts the existing `consolesChanged` event (from
  `SessionRegistry.close` itself) — pinned by a test, since the future Shells
  window's live updates depend on it.
- `POST /api/projects/{projectId}/shells` refuses a caller who is not the
  project's owner with 404, so a non-owner can no longer persist rows into an
  owner's `GET /api/shells` listing, block their project delete, or probe project
  existence.
- `SecurityConfig` requires authentication on `/api/shells` and
  `/api/projects/*/shells`; an unauthenticated call gets 401 from the common
  entry point, not a 500 from a null `Principal`.
- An engine test covers: closing deletes the row and a later listing no longer
  shows it; close refuses (404) a non-owner and a non-shell id; mint refuses
  (404) a non-owner; closing an open shell broadcasts `consolesChanged`.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- The Shells window UI and its creation triggers — #446, #447, #448.
- No audit or change of other endpoints' SecurityConfig matchers (e.g. the
  unmatched `PUT …/console/{sessionId}/name`) — this task adds exactly the
  shell-path matchers.
- No change to how any other session family closes, and no worktree removal of
  any kind.

## Decisions made along the way
- The mint-time owner check reuses `WorktreeSessionAuthorization.isVisibleTo` on
  the freshly minted session id, before the row is persisted (agent, 2026-08-31):
  the id already carries the project prefix that check keys on, so the exact
  same project-owner rule every listing and the WebSocket attach gate apply
  decides mint too — no second authorization path to drift, and no new method on
  `WorktreeSessionAuthorization` (keeping the diff to the classes the issue
  names).
- `ShellSessionService.close` mirrors `ProjectConsoleService.close`'s gate
  (family membership + a persisted row + owner visibility) and then delegates to
  `SessionRegistry.close`, which already kills the PTY, deletes the row, removes
  session uploads, and broadcasts `consolesChanged` for any project-prefixed id
  (agent, 2026-08-31). Unlike the console close, it deliberately performs no
  worktree-removal attempt: a shell never owns a directory.
- SecurityConfig gets two matcher lines (agent, 2026-08-31):
  `/api/projects/*/shells/**` — the trailing `/**` matches zero or more
  segments, covering both the collection POST and the per-session DELETE — and
  `/api/shells`. The issue's "two shell REST paths" wording predates the DELETE
  path this task itself adds; two lines with `/**` cover all three shapes.

## Deviations / notes
- none
