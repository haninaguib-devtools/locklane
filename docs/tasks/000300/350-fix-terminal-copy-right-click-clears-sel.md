# 350 — Fix terminal copy: right-click clears selection on macOS and Cmd+C fails silently

Issue: #350

## Asked
Copying text out of a console terminal is broken for macOS users, from two
independent causes in the browser terminal component: xterm.js's
`rightClickSelectsWord` option (on by default for macOS) replaces a drag selection
with the single word under the pointer the instant the user right-clicks, so the
context menu's Copy can never copy more than one word; and the custom Cmd/Ctrl+C
handler suppresses the browser's native copy path and then writes to the clipboard
with a bare `.catch(() => {})`, so a rejected write (Safari does not credit a keydown
as a sufficient user gesture; Chrome rejects when the site's clipboard permission is
blocked) fails completely silently with no fallback.

## Done when
- `Terminal` is constructed with `rightClickSelectsWord: false`.
- The Cmd/Ctrl+C handler falls back to a synchronous copy (off-screen textarea +
  `document.execCommand('copy')`) when the async Clipboard API is unavailable or its
  promise rejects, and logs rather than silently swallowing a failure the fallback
  can't recover from either.
- Unit tests cover the handler: chord with a selection prevents default and copies
  (including the fallback path when `writeText` rejects); chord without a selection
  falls through to xterm (interrupt behavior unchanged).
- `cd client && npm test` passes.
- Human-judged, on macOS: drag-select then right-click keeps the selection and
  context-menu Copy copies it; Cmd+C with a selection copies it in Safari and Chrome;
  Ctrl+C with no selection still interrupts the running process.

## Explicitly not
- No server/engine changes — entirely client-side.
- No Ctrl+X "cut" support — a terminal screen isn't editable.
- No paste changes — paste stays on the browser's native path.

## Decisions made along the way
- none

## Deviations / notes
- The human-judged macOS verification step (drag-select + right-click, Cmd+C in
  Safari/Chrome) is left for the human — not something this session can exercise.
