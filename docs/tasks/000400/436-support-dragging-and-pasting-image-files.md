# 436 — Support dragging and pasting image files into a console session
Issue: #436

## Asked
Let a user drag an image file onto a console terminal, or paste an image from the
clipboard, and have the CLI running in that session (claude/codex) receive it — the
way dropping a file on a native terminal types its path. The CLI runs on the server
while the image lives on the browser's machine, and browsers hand a page file
*contents*, never a usable path, so the bytes must be moved: the client uploads the
dropped or pasted file over the existing authenticated HTTP channel, the engine
writes it to a real file on the server, and the client injects that server-side path
into the PTY as a bracketed paste.

## Done when
- Dragging an image file onto a terminal uploads it and inserts the resulting
  server-side path at the prompt (`dragover`/`drop` default-prevented so the browser
  never navigates to the file); dropping something that is not a file leaves current
  behavior unchanged.
- Pasting with an image on the clipboard (a native `paste` event whose
  `clipboardData` carries a file instead of text) uploads it and inserts the path
  the same way; text pastes are untouched.
- A new engine endpoint (`POST /api/sessions/{id}/uploads`) accepts the file,
  authenticated and authorized exactly like the session's other APIs (only the
  project owner, per ADR-105), writes it under a per-session location on the server,
  and returns the absolute path; uploads over a configured size cap are refused with
  a clear status and the client surfaces the refusal instead of failing silently.
- Uploaded files are cleaned up when their session ends, and directory/folder drops
  are politely refused.
- The path is injected through the same bracketed-paste path as keyboard paste, so
  multi-line safety and CLI behavior match a typed path.
- Unit tests cover the client drop/paste-file handling and the engine endpoint
  (auth, size cap, storage location); `cd client && npm test` and `./mvnw -B test`
  pass.
- Human-judged: drag a screenshot into a claude session tab and confirm claude reads
  the image; paste a screenshot with the keyboard and confirm the same.

## Explicitly not
- No general file manager, no download direction (server → browser), and no
  non-image special-casing beyond refusing folders — this is path-injection for
  dropped/pasted files only.

## Decisions made along the way
- Uploads live under `${locklane.data-dir}/uploads/<sessionId>/` — one folder per
  session, so cleanup when the session ends is one recursive delete keyed by the id
  every closer already has (agent, 2026-08-31).
- Cleanup hooks into `SessionRegistry.close(sessionId)`: every path that ends a
  session (`WorktreeController`, `ProjectConsoleService`, `WorktreeCleanupSweeper`)
  already funnels through it, so no closer can forget (agent, 2026-08-31).
- Authorization reuses `WorktreeSessionAuthorization.isVisibleTo`, the same check
  the WebSocket attach and REST listings share; a session the caller may not see is
  404, matching `WorktreeController.closeSession` (agent, 2026-08-31).
- The endpoint is added to `SecurityConfig`'s explicit `authenticated()` matchers —
  the config ends in `anyRequest().permitAll()`, so a new API path is anonymous
  until listed there (agent, 2026-08-31).
- The client intercepts `paste` in the capture phase on the terminal container —
  the same pattern #435 used for right-click suppression — and only when
  `clipboardData` carries files, so text pastes never touch the new code. Paths are
  injected with xterm's own `paste()`, which applies bracketed-paste wrapping
  exactly like keyboard paste (agent, 2026-08-31).

## Deviations / notes
- `engine/src/main/resources/application.yml` gains the `locklane.uploads` defaults
  and raises `spring.servlet.multipart` limits (Spring's default is 1 MB, below the
  10 MB cap). The issue's Scope line names the engine's java tree; the yml is that
  endpoint's configuration and is read as part of the engine half of the scope —
  flagged here for the reviewer rather than silently assumed.
- `engine/src/test/resources/application.yml` gains the same `locklane.uploads`
  keys — that file *replaces* the main yml on the test classpath rather than
  merging with it, so a new `${...}` placeholder missing there fails every
  `@SpringBootTest` context load (65 errors on the first full run; caught by
  `./mvnw -B test` before anything was pushed).
