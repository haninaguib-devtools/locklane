# 831 — Add Open IDE button for a project's main checkout
Issue: #831

## Asked
On a project's page, add an "Open IDE" button beside the existing "Open shells" button.
Clicking it opens the effective IDE (the person's Settings choice, or code-server by
default) directly at the project's own main checkout — the same working directory
`project.workareaPath` that "Open shells" already opens a shell at when minted with no
issue number (`ShellsService.open(projectId, null, project.workareaPath)`, #445).

Today "Open IDE" only exists per agent-session tab, and the whole IDE-opening path
(`AgentSessionsController#openIde`, `CodeServerService`, `DesktopIdeLauncher`) is keyed
on an agent session id resolved through `SessionRegistry` — there is no existing session
identity for a project's bare main checkout that this plumbing recognizes. Shells solved
the equivalent problem for shells by giving the main checkout a session row of its own
(`ShellSessionService`, `mainCheckout: true`). This task does the same for IDE: give the
main checkout a session identity the existing open-ide plumbing already understands,
rather than adding a second, path-based way to launch an IDE that bypasses
`SessionRegistry`.

## Done when
- The project page shows an "Open IDE" button next to "Open shells".
- Clicking it opens the effective IDE (`DefaultIdeStore.effective()`, defaulting to
  code-server) at the project's main checkout.
- For code-server: the browser is sent to a proxied IDE URL scoped to that main-checkout
  session, the same way it already works for an issue-worktree or project-agent session.
- For a desktop IDE, from a direct-loopback caller: the editor launches with the main
  checkout as its opened folder, the same `LoopbackRequests.isDirectLoopback` gate the
  per-tab action already applies.
- Existing "Open IDE" behavior for issue-worktree sessions and project-agent sessions
  (#194) is unchanged.
- `./mvnw -B test` passes; a new engine test covers opening the IDE at a project's main
  checkout (the code-server URL/folder and the desktop-launch working directory).

## Explicitly not
- A new, path-based IDE-launch endpoint that opens an arbitrary directory without going
  through `SessionRegistry` — that was the alternative design considered and not chosen
  for this task.
- Any behavior change to the shells feature itself, beyond whatever it needs to also
  expose to the IDE plumbing.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The main-checkout session identity lives in a new `ProjectIdeSessionService`
  (`dev.locklane.engine.persistence`), sibling to `ShellSessionService` rather than an
  extension of it: unlike a shell, a single IDE at the main checkout is meant to be
  reused across opens (`code-server` already reuses a running process for a session id
  it has seen before), so the id is canonical (`"<projectId>-ide-main"`, no per-click
  suffix) and minting is idempotent — `open()` upserts the same row every call. A new
  `POST /api/projects/{projectId}/consoles/main-checkout-ide` endpoint on
  `AgentSessionsController` mints/reuses it; the existing `POST .../{id}/open-ide`
  endpoint then opens it unchanged, recognizing the new id family via
  `ProjectIdeSessionService.isOpenAndVisibleTo` alongside its existing
  `allWorktreeIds` check (agent, 2026-09-08).
- Deliberately excluded the new session family from `IssueWorktreeService`'s
  `allWorktreeIds` (keeps it out of the header indicator/picker and every
  agent-session-tab listing, the same as a shell) and from its
  `hasAnySessions`/`deleteSessionsForProject` sweep: unlike a shell, this session has
  no explicit close and is meant to live for as long as the project itself does —
  counting it in `hasAnySessions` would block a project delete forever the first time
  its IDE was ever opened, since there is no button to close it first. Its row is
  simply left behind, harmless and never visible again once the project (and so its
  ownership) is gone, on the rare path where the project is deleted anyway (agent,
  2026-09-08).
- No changes were needed to `SessionRegistry`, `CodeServerService`, or
  `DesktopIdeLauncher`: all three already resolve a working directory purely from the
  session id via the shared `WorktreeSessionRepository` row, with no assumption about
  which family minted it (agent, 2026-09-08).

## Deviations / notes
- The issue's Scope line names `client/src/app/components/project-summary/*` for the
  frontend but not `client/src/app/services/agent-sessions.service.ts`. Wiring the new
  button requires one more HTTP-calling method on that same service the component
  already injects and calls (`openIde`, `notifyOpened`) — the minimal, conventional
  place for it, not a drive-by change to unrelated behavior. Added
  `openMainCheckoutIdeSession` there rather than inlining an HTTP call in the
  component or duplicating the service (agent, 2026-09-08).
- The issue's Scope also lists `ShellSessionService` "(or wherever the main-checkout
  session identity ends up minted/registered)" — resolved as a new sibling class,
  `ProjectIdeSessionService`, per the Decisions entry above, rather than an edit to
  `ShellSessionService` itself.
