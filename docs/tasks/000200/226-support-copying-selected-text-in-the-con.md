# 226 — Support copying selected text in the console
Issue: #226

## Asked
In the app's console/terminal panels, a user can highlight/select text but cannot copy
it: xterm.js sends Ctrl/Cmd+C straight to the PTY as an interrupt (SIGINT), and the
terminal's canvas has `user-select: none` so there is no real DOM selection either. Fix
this so selected console text can be copied, without breaking Ctrl+C's normal role of
interrupting a running process when nothing is selected.

## Done when
- With text selected in a console, pressing Ctrl+C (or Cmd+C on macOS) copies the
  selected text to the system clipboard instead of sending an interrupt to the running
  process.
- With no text selected, Ctrl+C still sends an interrupt to the console's process,
  unchanged from current behavior.
- A human verifies in the browser: select some console output, press Ctrl/Cmd+C, and
  paste it into another application to confirm the text matches.

## Explicitly not
Paste support and any other terminal keyboard shortcuts are out of scope for this task.
A right-click "Copy" context-menu affordance was not implemented — the done-when
criteria only requires the Ctrl/Cmd+C path, and it doesn't depend on real DOM selection.

## Decisions made along the way
- none

## Deviations / notes
- none
