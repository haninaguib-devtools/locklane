# 649 — Hide xterm 6's leftover viewport scrollbar that shows as a strip right of the console
Issue: #649

## Asked
Every console tab shows a stray vertical strip down its right edge, between the terminal
and the window edge, whenever macOS draws classic (always-visible) scrollbars — a mouse
connected, or "Show scroll bars: Always". Light in the LockLane app window (Chromium), a
black bar with white end caps in Safari. It is a real scrollbar: xterm 6 moved scrolling
into a new `.xterm-scrollable-element` box with its own 14px overlay scrollbar, but still
creates the old, now-empty `.xterm-viewport` div, styled by xterm's own stylesheet as
`position: absolute; inset: 0; overflow-y: scroll; background-color: #000`. On a bare
xterm page the scrollable element covers that div, but this client puts 8px of padding
on the `.xterm` element (#122), and the absolutely positioned viewport spans the padding
box, so it pokes out on every side. Measured in devtools on a 1281px-wide console: the
viewport is 1281px wide, the scrollable element 1265px; the 16px difference is the
native scrollbar gutter. The same leftover div is why the 8px ring around every console
is pure `#000` instead of the theme background `#1c1a17`.

Fix it so the leftover viewport div neither reserves a scrollbar nor shows through the
padding: the console fills its box edge to edge, with the 8px ring in the theme
background colour. Natural home: a global rule in `client/src/styles.css` next to the
existing `.terminal .xterm { padding: 8px }` rule (Angular's view encapsulation cannot
reach xterm's runtime-created elements). Before hiding the element outright, confirm
from `@xterm/xterm` 6.0's source that nothing still routes through the viewport div —
wheel events, `scrollLines`, the DOM renderer fallback (#616). Prefer the least invasive
rule that passes that check.

## Done when
- `grep -n 'xterm-viewport' client/src/styles.css` finds a rule under `.terminal` that
  stops the viewport div from reserving a native scrollbar and gives it the theme
  background `#1c1a17` (or removes it from rendering entirely); the rule's comment
  records what in xterm 6's source was checked to conclude the element is safe to
  neutralise.
- Human check: with a mouse connected (or "Show scroll bars" set to Always), a console
  tab shows no strip on its right edge in the LockLane app window and in Safari; the
  dark area reaches the window edge, and the 8px ring is the theme colour, not black.
- Human check: wheel/trackpad scrolling, dragging the scrollbar of a long session,
  selecting text, and switching console tabs all behave as before, with WebGL active
  (`document.querySelector('.xterm-screen canvas')` exists) and with the DOM renderer.
- `./mvnw -B test` passes (the client build is a build input) and
  `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No change to the fit/resize logic, the 8px padding itself, the debounce, the tab
  layout, or the PTY size protocol.
- No xterm version bump — against `@xterm/xterm` ^6.0.0 as pinned today.
- The fit remainder (the few unused pixels right of the last column, inside the
  terminal's own dark background) is expected and not part of this.

## Decisions made along the way
- `display: none` rather than `overflow: hidden` + a theme background: the element is
  inert in @xterm/xterm 6.0.0 (created and appended in `CoreBrowserTerminal.open()`;
  wheel handling and scrolling live on the scrollable element `Viewport.ts` builds on
  the screen element; `OverviewRulerRenderer` only uses it as a DOM insertion anchor
  for an option this app never sets, and `DomRenderer` only copies its never-set
  inline `style.height`), so hiding it is the least invasive rule — one declaration,
  and the padding ring shows `.terminal`'s own `#1c1a17` instead of a second copy of
  the theme colour that could drift from the one in `terminal.component.ts`
  (claude, 2026-09-03).
- The 6.0.0 source was read from an older workarea's installed copy
  (`@xterm/xterm@6.0.0`, the version this checkout's lockfile resolves to), since this
  worktree had no `node_modules` before the Maven build (claude, 2026-09-03).

## Deviations / notes
- The task branch was created from `origin/main` directly: the local `main` ref is
  checked out in the primary workarea and behind-only, so it could not be
  fast-forwarded from this worktree; the branch point is the same commit a
  fast-forward would have produced.
