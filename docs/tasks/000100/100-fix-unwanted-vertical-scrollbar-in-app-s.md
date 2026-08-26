# 100 — Fix unwanted vertical scrollbar in app shell
Issue: #100

## Asked
The app shows a vertical scrollbar all the time, even though the page content isn't
actually taller than the viewport — it only scrolls a tiny amount. Likely cause: the
header taking up more height than the layout accounts for. The scrollbar should only
appear when the window is genuinely too small to fit the content.

## Done when
- With a normal-sized browser window, no vertical scrollbar is visible anywhere in the app.
- Shrinking the window (or content) so it genuinely overflows still shows a scrollbar.
- Verified visually in the browser by a human.

## Explicitly not
None.

## Decisions made along the way
- `.topbar` in `app.component.css` hardcoded `height: 60px`. If the header's actual
  content needs a hair more than that, the excess isn't clipped anywhere in the shell
  and bubbles up as page-level scroll overflow — exactly the "tiny amount" the issue
  describes. Replaced the fixed height with `padding: 16px` so the header's box always
  sizes to fit its own content instead of a guessed number (hani, 2026-08-26).

## Deviations / notes
- Could not visually verify in a real browser this session (no Chrome extension
  connection available) — needs the human eyeball check called for in Done-when.
- Pre-existing, unrelated test failure noted during checks:
  `SessionRegistryReattachTest.closeStopsTheSessionAndForgetsItsRecord` in the `engine`
  module (PTY/session code) times out waiting on a condition, reproduces in isolation,
  and is untouched by this diff (client-CSS only). Flagging for the human rather than
  fixing — out of scope here.
