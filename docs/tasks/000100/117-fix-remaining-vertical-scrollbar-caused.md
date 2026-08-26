# 117 — Fix remaining vertical scrollbar caused by vh/% unit mismatch
Issue: #117

## Asked
After #100 shipped (changing `.topbar` padding), the app shell still shows an unwanted
vertical scrollbar at normal window size. The likely real cause was never addressed:
`client/src/app/app.component.css` sets `:host { height: 100vh; }`, while its ancestors
(`html`, `body` in `client/src/styles.css`) use `height: 100%`. Mixing `vh` and `%` units
across the same height chain can produce a sub-pixel mismatch in some browsers — just
enough overflow to trigger a scrollbar even though nothing is visibly too tall.

## Done when
- `:host` in `app.component.css` uses `height: 100%` (or another approach that keeps the
  whole height chain in the same unit) instead of `100vh`.
- With a normal-sized browser window, no vertical scrollbar is visible anywhere in the app.
- Shrinking the window (or content) so it genuinely overflows still shows a scrollbar.
- Verified visually in the browser by a human.

## Explicitly not
None.

## Decisions made along the way
- Changed `:host { height: 100vh }` to `height: 100%` in `app.component.css`. `html` and
  `body` (`styles.css`) already use `height: 100%`, so matching units the whole way down
  removes the `vh`/`%` sub-pixel mismatch that was the likely cause of the scrollbar
  (hani, 2026-08-26).

## Deviations / notes
- Could not visually verify in a real browser this session (no Chrome extension
  connection available) — needs the human eyeball check called for in Done-when.
