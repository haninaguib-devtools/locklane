# 139 — Engine: project-level agent console session
Issue: #139 · Part of: #138

## Asked
Add a project-scoped console session so an agent CLI can run in the project's main
checkout (not an issue worktree). Reuses the existing PTY + WebSocket plumbing
(`PtySession`, `SessionRegistry`, `TerminalWebSocketHandler`) with a new session kind
whose working directory is the checkout `ProjectCheckoutService` maintains. Inject the
project's decrypted GitHub token as `GH_TOKEN` into the PTY environment so `gh` (and
thus the `/t-open` skill) works inside the session. Persist/reattach semantics are
identical to worktree sessions — this is what lets #138's "discuss and open new work
before any issue exists" flow have a real shell to run `/t-open` in.

## Done when
- `POST /api/projects/{id}/console` (and `GET` to discover an existing one) creates/
  returns a project-level session; `DELETE` tears it down.
- The WebSocket attach path serves the session with `cmd=claude|codex|shell`,
  `cwd` = project checkout, with buffered replay on reattach — same framing as
  existing sessions.
- The PTY env for project-level sessions contains `GH_TOKEN` from the project's
  stored token when one is configured; running `gh auth status` inside such a
  session succeeds using it.
- Ownership/auth checks match worktree sessions (only the owning authenticated user
  attaches).
- `./mvnw -B test` passes with new coverage for the controller and session-kind
  wiring.

## Explicitly not
- No client UI (sibling task, #140).
- No new issue-creation API — the agent uses `gh` from the shell (boundary).

## Decisions made along the way
- **No discriminator column.** A project-level console session's id is minted
  deterministically as `"<projectId>-console"` and stored in the existing
  `worktree_sessions` table exactly like any other session — recognized by its id
  shape, the same convention `IssueWorktreeService` already uses to tell a
  `<projectId>-<issueNumber>-...` worktree id apart from a bare `"main"` id. This
  shape never collides with `IssueWorktreeService`'s `^(\d+)-(\d+)-` prefix (a
  single numeric group followed by a literal, not two numeric groups), so it is
  correctly excluded from the per-issue/console pickers those methods already
  serve, matching this task's "no client UI yet" non-goal. Nothing in the acceptance
  criteria needs to *query by* session kind, only to *recognize* one from its own id
  at attach/env-resolution time, so the discriminator column the issue mentioned as
  a possibility turned out not to be needed (haninaguib, 2026-08-26).
- **New `ProjectConsoleService`** (`persistence`) owns the project-console id
  convention end to end: minting/finding the deterministic session id, the same
  owner-visibility rule `IssueWorktreeService` already applies (unclaimed or
  owned-by-the-requester is visible, anyone else's is not), and resolving the
  `GH_TOKEN` environment override for a given session id (decrypts the project's
  stored token via `TokenCipher`, the same one `ProjectGhResources` already uses for
  `gh` fetches, #81). Kept separate from `IssueWorktreeService`/
  `WorktreeCreationService` rather than folded in: those are keyed on
  project+issue, this on project alone, and mixing the two id shapes into one
  service would make both harder to read.
- **`SessionRegistry.attach` gained an `extraEnvironment` overload** (merged over
  `System.getenv()`, consulted only on a session's first attach — same "first attach
  only" rule already governing `launchCommand`/`columns`/`rows`, since a reattach
  reaches the process already running with whatever environment it started with).
  Existing overloads are unchanged and delegate to it with `Map.of()`, so no other
  caller's behavior changes.
- **`TerminalWebSocketHandler` now takes a `ProjectConsoleService`** and asks it for
  `environmentFor(sessionId)` on every attach, passing the result straight through
  to `SessionRegistry.attach`. For any session id that isn't shaped like a project
  console, this is an empty map — a no-op — so ordinary worktree/main-checkout
  sessions are unaffected. Touching this WS entry point mirrors #29's precedent
  for the same reason: it is where a session's launch parameters are actually
  assembled, and the done-when's environment requirement cannot be met without
  reaching it.
- **New endpoint group `/api/projects/{id}/console`** (`ProjectConsoleController`):
  `GET` discovers an existing session (404 if none, or if it exists but belongs to
  another user), `POST` mints/returns one (404 for an unknown or not-yet-`READY`
  project — same rule `WorktreeCreationService.startSession` already applies, no
  owner check at this step since, like the existing worktree-creation endpoint,
  the real ownership gate is the WebSocket attach itself), `DELETE` tears it down
  (404 if it doesn't exist or isn't visible to the caller) — same shape and
  response body keys (`sessionId`, `workingDirectory`) as `WorktreeController`'s
  existing `worktreeId`/`workingDirectory` pair.

## Deviations / notes
- **Touched `engine/src/main/java/dev/locklane/engine/security/SecurityConfig.java`**,
  outside this task's declared scope
  (`engine/src/main/java/dev/locklane/engine/{persistence,pty,ws,github}/**`), to add
  `.requestMatchers("/api/projects/*/console").authenticated()` alongside the
  existing worktree/consoles matchers. Without it the new endpoints would fall
  through to `.anyRequest().permitAll()` — a real auth gap, and the done-when says
  outright that "ownership/auth checks match worktree sessions". Treated as
  necessary plumbing for the scoped change, the same reasoning #29's record gives
  for its own out-of-scope touch of `TerminalWebSocketHandler`.
- Manually verified end to end against a real (throwaway) git checkout and a real
  GitHub token: started the engine on an isolated port/data-dir, added a project,
  set its GitHub token via the existing `/api/projects/{id}/github-token` endpoint,
  called `POST /api/projects/{id}/console`, attached over the WebSocket at the
  returned `workingDirectory` with `cmd=shell`, and confirmed `gh auth status`
  printed the token's own account — proving the injected `GH_TOKEN` is what `gh`
  actually authenticates with inside the session, not just present in `env`
  (haninaguib, 2026-08-26).
