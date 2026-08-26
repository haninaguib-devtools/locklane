# 62 — Resize the PTY to match the browser terminal's actual size
Issue: #62

## Asked
The browser's terminal pane (xterm.js) fits itself to its container, but that size is
never told to the server. The server-side PTY (pty4j) is started at whatever pty4j's
default is and never resized after that. The result: a full-screen terminal app running
in a console tab — including Claude Code itself — renders as if the terminal were
narrower than the pane and never uses the full width.

## Done when
- A new console session's PTY starts at the browser terminal's actual columns/rows, not a
  hardcoded default.
- When the browser terminal's size changes (window resize, or a hidden tab becoming
  visible and re-fitting), the new size is sent to the server and the PTY is resized to
  match (pty4j's `PtyProcess.setWinSize`).
- A full-screen TUI app (e.g. `claude`) running in a console tab visibly fills the full
  width/height of its pane after a resize, not just at first load.
- `TerminalWebSocketHandler` distinguishes a resize message from raw keystroke input
  (currently every inbound WebSocket text message is forwarded verbatim to the PTY as
  input) without breaking any character a user can actually type.
- New tests cover the resize round-trip: client sends new dimensions, server resizes the
  right session's PTY.

## Explicitly not
none

## Decisions made along the way
- Every inbound WebSocket message the client sends now carries a one-character type
  tag it always prepends itself (`0` = keystroke input, `1` = resize), rather than a
  reserved character or JSON envelope. Since the client is the only producer of these
  messages and always wraps them, no raw keystroke content can ever collide with the
  tag — this was needed to satisfy the issue's explicit requirement that a resize
  message be distinguishable from raw input without breaking any character a user can
  actually type. Existing WebSocket integration tests that sent raw `"echo ...\n"`
  messages were updated to the `"0echo ...\n"` form to match.

## Deviations / notes
- none
