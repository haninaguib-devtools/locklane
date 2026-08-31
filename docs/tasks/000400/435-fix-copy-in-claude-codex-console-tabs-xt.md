# 435 — Fix copy in claude/codex console tabs: xterm.js drops OSC 52
Issue: #435

## Asked
Copying out of a claude/codex console tab does not work, even though the plain-shell
copy paths (#226/#350) are fine. Claude Code enables full terminal mouse tracking, so
the selection the user sees belongs to claude, not xterm.js: Cmd/Ctrl+C finds no xterm
selection, right-click forwards a mouse press that makes claude drop its selection, and
claude's own Ctrl+C copy emits OSC 52 — the standard "terminal, write this to the
clipboard" escape — which the shipped xterm.js bundle silently drops because no OSC 52
handler is registered. Fix: load `@xterm/addon-clipboard` (implements OSC 52 against
the browser clipboard) on every terminal, and stop forwarding the right-click button
press to the PTY when the application has mouse tracking enabled, so the CLI's
selection survives the context-menu click.

## Done when
- Every terminal loads `@xterm/addon-clipboard` (ClipboardAddon), so an OSC 52
  clipboard write from the PTY application lands in the browser's clipboard.
- Right-clicking over a terminal whose application has mouse tracking enabled no
  longer clears that application's selection (the button-2 event is not forwarded to
  the PTY); right-click behavior in plain shell tabs is unchanged.
- The existing xterm-selection copy path is unchanged: Cmd/Ctrl+C with an xterm
  selection still copies, and Ctrl+C with no selection still interrupts.
- Unit tests cover: the clipboard addon is loaded; button-2 suppression applies only
  under mouse tracking; the existing copy-chord tests still pass.
- `cd client && npm test` passes; `./mvnw -B test` passes.
- Human-judged on macOS against https://locklane.javalib.dev (Chrome and Safari):
  claude-tab drag-select + Ctrl+C + paste round-trips; right-click no longer clears
  the selection; Shift+drag then Cmd+C copies via the browser path; plain shell copy
  unchanged. A Safari refusal of the clipboard write outside a user gesture is logged
  and noted, not silently swallowed.

## Explicitly not
- Browser context-menu Copy of a CLI-owned selection — structurally impossible; the
  selection lives in the CLI process on the server and OSC 52 is the only channel out.
- The engine's 8KB per-WebSocket-message cap (large pastes drop the connection) and
  the Linux/Windows Ctrl+V-never-pastes mapping — both verified during diagnosis of
  #435 but out of scope; to be opened separately if wanted.
- Image/file drag-and-drop and clipboard-image paste — split to #436.

## Decisions made along the way
- `@xterm/addon-clipboard` pinned at `^0.2.0` (agent, 2026-08-31): 0.2.0 is the
  npm `latest` tag, published 2025-12-22 alongside `@xterm/xterm` 6.0.0 (the same
  release train that made `@xterm/addon-fit` 0.11.0, already in use here); the
  0.3.0-betas track the xterm 6.1 beta train.
- Custom `IClipboardProvider` instead of the addon's default `BrowserClipboardProvider`
  (agent, 2026-08-31), for two reasons. Writes: the default provider returns
  `navigator.clipboard.writeText`'s promise straight into the OSC handler, so a Safari
  rejection (server output carries no user-gesture credit) would disappear as an
  unhandled rejection — exactly the silent failure the issue forbids; routing writes
  through the component's existing `copyToClipboard` (renamed from `copySelection`, it
  now serves both copy paths) gets the #350 execCommand fallback and a logged failure
  for free. Reads: the default provider answers an OSC 52 read (`52;c;?`) with the real
  clipboard contents, letting any PTY application silently read the user's clipboard —
  a capability nobody asked for and most terminals ship disabled; ours answers with an
  empty report.
- Right-click suppression is a capture-phase `mousedown` listener on the component's
  container, gated on `term.modes.mouseTrackingMode !== 'none'` (public xterm API),
  rather than filtering mouse-report sequences out of `onData` (agent, 2026-08-31).
  Verified against the installed xterm 6.0.0 source: the button-press report comes from
  an "always on" `mousedown` listener on xterm's element, and the matching release
  handler is only registered *during* that listener — so stopping propagation of the
  button-2 press before it reaches xterm suppresses both the press and release reports,
  with no escape-sequence parsing. Plain tabs are untouched because the gate reads
  `'none'` there, and Shift+drag still works because it uses button 0.

## Deviations / notes
- `client/package-lock.json` changed alongside `client/package.json`: the lockfile is
  the mechanical twin of the declared dependency, not extra scope.
