# 445 — Track and list shell-kind console sessions
Issue: #445 · Part of: #444

## Asked
The engine's `/ws/sessions/{id}` endpoint already spawns a plain shell PTY at any
`dir` with `cmd=shell` — what is missing is a way to mint a shell session with a
structured, queryable identity and a way to list every open shell a user has,
grouped by project and by issue/main, so a client can build a sidenav of them.
This task adds both, with no client-facing UI of its own — it lands the engine
capability the Shells window and its two creation triggers (separate tasks under
#444) will call into.

## Done when
- A creation endpoint accepts a project id, an optional issue number, and a
  worktree directory (main checkout or a specific task worktree), mints a new
  shell session id in a structured, parseable shape, persists it, and returns the
  session id ready to attach a WebSocket to with `cmd=shell`.
- A listing endpoint returns every open shell session for the authenticated user,
  each with its session id, project id, issue number (absent for a project-main
  shell), and whether it targets the project's main checkout or a specific
  worktree — sufficient for a client to group and label them without extra
  per-session calls.
- Shell sessions are excluded from every existing "list consoles for this
  issue/project" query used by the console tabs today, so they don't leak into
  the existing UI.
- An engine test covers: creating a shell session returns a well-formed id and
  persists it; the listing endpoint returns it grouped correctly; an existing
  agent-session listing query does not include it.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- The Shells window UI, and the two creation-trigger buttons that call this
  endpoint — separate tasks (#446, #447, #448 under #444); this task only lands
  the engine capability.
- Any change to how `cmd=shell` itself spawns a PTY — that already works.

## Decisions made along the way
- Shell session id shape: `<projectId>-shell-<issueNumber>-<8hex>` for a shell at
  an issue's worktree, `<projectId>-shell-main-<8hex>` for one at the project's
  main checkout (agent, 2026-08-31). The literal `shell` second segment can never
  collide with the existing families — `IssueWorktreeService`'s `^(\d+)-(\d+)-`
  needs a numeric second segment and `ProjectConsoleService`'s
  `^(\d+)-console(-.+)?$` needs the literal `console` — so every existing
  console-tab listing excludes shells with no code change, while the leading
  `<projectId>-` gives shells the same project-owner-derived visibility
  (`WorktreeSessionAuthorization`) and WebSocket attach gate every session
  already has.
- Creation persists the session row immediately (`recordAttach` at mint time)
  rather than waiting for the first WebSocket attach the way agent sessions do
  (agent, 2026-08-31): the issue's done-when says the returned id is "persisted
  … and ready to attach a WebSocket to", and persisting up front also lets the
  attach omit `?dir=` — `SessionRegistry.lastKnownWorkingDirectory` resolves the
  directory from the row.
- `IssueWorktreeService.hasAnySessions` and `deleteSessionsForProject` learn the
  shell shape (agent, 2026-08-31): both are system-level sweeps over "every
  session belonging to this project", and a shell session left out of them would
  let a project delete orphan an open shell, or a user cascade-delete leave shell
  rows behind. This is inclusion in safety gates, not in the console-tab listings
  the issue excludes shells from.
- No `ws/` change proved necessary (agent, 2026-08-31): the scope allowed
  id-shape awareness there, but the WebSocket handler resolves authorization via
  `WorktreeSessionAuthorization` (prefix-based, already covers shells) and the
  working directory via the persisted row, and `cmd=shell` already launches the
  default shell. Nothing in `ws/` reads family shapes directly.

## Deviations / notes
- The two new REST paths (`POST /api/projects/{projectId}/shells`,
  `GET /api/shells`) are not added to `SecurityConfig`'s authenticated-matcher
  list — `SecurityConfig.java` lives in `security/`, outside this task's declared
  scope. They fall to the existing `anyRequest().permitAll()` default, where an
  unauthenticated call fails on the missing `Principal` before touching any data
  (the same posture as the existing unmatched sub-paths, e.g.
  `PUT …/console/{sessionId}/name`). Proposed follow-up for the human: add
  `/api/shells` and `/api/projects/*/shells` matchers in `SecurityConfig` (and
  ideally the other unmatched sub-paths) as a small hardening task.
