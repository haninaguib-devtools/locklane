# 211 — Fix background terminal tabs mis-sizing after page rebuild
Issue: #211

## Asked
When a page containing multiple console tabs is torn down and rebuilt (e.g. navigating
to a different page and back), the terminal for a tab that isn't currently selected gets
its very first xterm.js `term.open()` call while its container is hidden
(`display:none` via the `tab-hidden` class). xterm measures and caches its
character-cell size at `open()` time; measuring against a hidden (0×0) container caches
a bad size, and the later `fitAddon.fit()` call that runs when the tab is eventually
clicked doesn't force a fresh remeasure — it just fits against the already-bad cached
metrics. The terminal (and whatever process is running inside it) ends up with a wrong
size until something unrelated forces a real resize, such as dragging the sidenav
separator.

## Done when
- Repro no longer reproduces: on a page with two console tabs (tab B selected),
  navigate to a different page and back, then click tab A — tab A's terminal is sized
  correctly the moment it's shown, without needing to drag the sidenav separator or
  trigger any other resize.
- `client/src/app/components/terminal/terminal.component.ts` no longer calls
  `term.open()` for a tab that is not active at construction time; `term.open()`
  happens on that tab's first activation instead.
- Existing behavior is preserved: a background tab still receives and buffers output
  (scrollback) while hidden, and switching tabs never drops the underlying
  session/connection (#30).
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
None — this is a self-contained bug fix.

## Decisions made along the way
- none

## Deviations / notes
- none
