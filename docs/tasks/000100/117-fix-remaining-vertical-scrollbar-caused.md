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
- The unit change alone made things worse — both scrollbars flickered on and off
  (hani, in-session, 2026-08-26). Real mechanism found: nothing in the shell clips
  overflow at the page level, and the terminal component refits itself (ResizeObserver →
  FitAddon.fit) whenever its container resizes. A transiently over-sized xterm canvas
  overflows the page → browser shows scrollbars → scrollbars shrink the viewport →
  terminal container resizes → refit → canvas fits → scrollbars vanish → container grows
  → refit → overflows again. A two-state oscillation; the old `100vh` merely froze it
  into the "persistent tiny scrollbar" state that #100 originally described. Moving the
  sidenav slider to a width where both states agree stops the flicker — exactly the
  observed behavior.
- Fix: `overflow: hidden` on the app shell's `:host`. The shell is a full-window app
  whose panels scroll internally (sidenav `overflow-y: auto`, project summary
  `overflow-y: auto`, terminal scrollback), so the page never needs to scroll; clipping
  at the root removes the scrollbars' feedback into layout and the loop has no fuel
  (hani, 2026-08-26).
- Rewrap fix (scope-expanded, see Deviations): the terminal's ResizeObserver fit is
  debounced 150ms (`FIT_QUIET_MS` in `terminal.component.ts`) so a drag or live window
  resize produces one fit — and therefore one PTY column change and one CLI redraw —
  after the size settles, instead of one per pixel. The mount-time fit and the
  tab-activation fit stay immediate: those are single events where a visible 150ms lag
  would be pure loss. No spec added — the component has none today (it is a thin wrapper
  over xterm's DOM), and simulating ResizeObserver in the test rig wasn't judged worth
  it for a timing guard (hani, 2026-08-26).

## Deviations / notes
- Deviation from the issue's Done-when: "shrinking the window so it genuinely overflows
  still shows a scrollbar" is now satisfied by the *inner panels* scrolling (sidenav,
  summary, terminal scrollback), never by a page-level scrollbar — the page-level one is
  exactly the thing that fed the flicker loop and is deliberately gone for good. Flagged
  to hani in-session 2026-08-26; ships only with their visual confirmation, which
  Done-when requires anyway.
- Could not visually verify in a real browser this session (no Chrome extension
  connection available) — needs the human eyeball check called for in Done-when.
- Scope expanded with hani's in-session approval (2026-08-26): the related rewrap defect
  — dragging the sidenav slider (or live-resizing the window) refits the terminal on
  every pixel of movement, streaming column-count changes to the server whose reflow
  permanently rewraps CLI scrollback ("claude cli wraps") — is fixed *in this task*
  rather than split to its own issue. Hani chose bundling over shipping the scrollbar
  fix alone (option 2 of 2 offered). Adds
  `client/src/app/components/terminal/terminal.component.ts` to the issue's original
  Scope of `app.component.css`; the issue body stays as opened, per the
  intent-changes-live-in-the-record rule.
- Residual known behavior, not a defect of this task: a *single* settled resize can
  still rewrap old CLI output a little — inherent to terminals + full-width CLI
  redraws — but it is one-shot instead of continuous.
