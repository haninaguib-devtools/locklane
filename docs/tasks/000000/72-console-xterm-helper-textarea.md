# 72 — Console xterm helper textarea is invisible but grabs clicks like a button
Issue: #72

## Asked
xterm.js renders a hidden helper `<textarea class="xterm-helper-textarea">` that it uses
internally to capture keyboard focus and typing. It's currently being mistaken for a
clickable button in the console UI rather than recognized as an invisible input. It
should not be visible or behave like a clickable UI control.

## Done when
- The xterm helper textarea no longer visually appears as, or can be interacted with as,
  a button in the console UI.
- Clicking/typing in the terminal still works normally (the helper textarea must keep
  functioning for keyboard capture).

## Explicitly not
none

## Decisions made along the way
- Root cause: xterm.js ships its own stylesheet (`@xterm/xterm/css/xterm.css`) that hides
  `.xterm-helper-textarea` (opacity 0, sized to the caret) and styles the rest of the
  terminal chrome. That stylesheet was never imported into the Angular build, so the
  helper textarea rendered unstyled — full-size and visible near the cursor, which reads
  as a stray clickable control. Fix: add `node_modules/@xterm/xterm/css/xterm.css` to the
  `styles` array in `client/angular.json` for both the `build` and `test` configurations.

## Deviations / notes
none
