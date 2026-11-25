# 1021 — Keep terminal focus after a fallback copy so paste keeps working
Issue: #1021

## Asked
Pasting into an agent or shell terminal sometimes does nothing, mostly when locklane is opened from another machine (e.g. a laptop) over plain `http://`. Cause: that page is not a secure context, so `navigator.clipboard` is undefined and every terminal copy — Cmd/Ctrl+C on an xterm selection, and OSC 52 clipboard writes the agent CLI sends on its own — goes through `copyFallback` in `client/src/app/components/terminal/terminal.component.ts`. The fallback focuses a temporary off-screen textarea to run `execCommand('copy')`, then removes it, leaving focus on `document.body` instead of the terminal. The next keystroke or Cmd+V reaches nothing until the user clicks back into the terminal; because OSC 52 writes arrive asynchronously with server output, the focus loss looks random. Make the fallback put focus back where it was so copying never takes the keyboard away from the terminal.

## Done when
- `copyFallback` records `document.activeElement` before focusing its textarea and, after removing the textarea, restores focus to that element when it is still in the document (xterm's helper textarea in the normal case).
- A unit test in `terminal.component.spec.ts` with `navigator.clipboard` absent shows focus is back on the previously focused element after a fallback copy (both the Cmd/Ctrl+C selection path and the OSC 52 path, or the shared fallback directly).
- The client test suite passes.

## Explicitly not
- Serving locklane over HTTPS / TLS.
- Changing the file-paste upload path (`uploadPastedFiles`), e.g. clipboards that carry both text and an image.

## Decisions made along the way
- Focus is restored with `preventScroll: true` so the page never jumps, and only to an
  element still in the document that is not `<body>` (nothing to give back otherwise).
- When the previously focused element was a text input/textarea, its selection range is
  restored too, so a copy never moves the caret.
- Both new specs were confirmed to fail with the restore call commented out and pass
  with it. The Cmd/Ctrl+C spec lets the mount's deferred focus/connect timers run
  before copying; without that, the mount's own `term.focus()` re-focused the terminal
  after the copy and hid the bug.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-opus-5-5
