# 122 — Fix terminal bottom clipping caused by FitAddon miscounting padded container height
Issue: #122

## Asked
The console terminal draws about one row too many, so its last row (e.g. the CLI's
status line) hangs below the window edge and is clipped. `.terminal` carries
`padding: 8px`, and the global `box-sizing: border-box` makes
`getComputedStyle(container).height` return the border-box height, padding included.
xterm's FitAddon uses that value as available space and only subtracts padding found on
the `.xterm` element itself (which had none), so it overestimates by 16px in each
dimension. Fix: move the 8px padding off the fit-measured `.terminal` container and onto
the `.xterm` element instead, so FitAddon subtracts it correctly. Visual appearance stays
the same; the row/column count becomes right.

## Done when
- `.terminal` in client/src/app/components/terminal/terminal.component.css has no
  `padding` declaration, and the 8px padding is applied to the `.xterm` element via a
  rule FitAddon accounts for.
- Human check: with a console open in a real browser, the CLI's bottom status line is
  fully visible with the dark 8px inset below it, at several window heights.
- Human check: text no longer runs flush against the terminal's right edge.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
No change to the fit debounce or resize behavior from #117, and no change to xterm
options or the terminal component's TypeScript.

## Decisions made along the way
- The new `.xterm` padding rule lives in client/src/styles.css (global), since Angular's
  view encapsulation on terminal.component.css cannot reach the runtime-created `.xterm`
  element — matches the issue's proposed fix exactly (haninaguib, 2026-08-26).

## Deviations / notes
- none
