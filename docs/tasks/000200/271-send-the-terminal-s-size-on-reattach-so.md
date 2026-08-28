# 271 — Send the terminal's size on reattach so an existing PTY isn't left at a stale size
Issue: #271

## Asked
Reopening or reloading a console whose process is already running leaves that process at
whatever terminal size it last knew, while the browser terminal displays at the current
window's size — so a full-screen CLI (e.g. Claude Code) keeps rendering at the old width
and its output appears mis-wrapped/garbled until the user happens to resize the window.
Make a reattaching client always deliver its real size to the running PTY.

The engine deliberately ignores the `cols`/`rows` connect-URL parameters on a reattach, so
the size must arrive as a `'1'`-tagged resize message. But since #268 moved `connect()`
after `fitAddon.fit()` inside the deferred-init timeout in `terminal.component.ts`, the
resize event the fit fires is dropped: xterm's `onResize` fires synchronously inside
`fit()`, while `this.session` is still null at that point (it's the third statement in the
same callback, after `fit()` and `focus()`). A second gap: if the fitted size happens to
equal xterm's 80x24 constructor defaults, `onResize` never fires at all.

## Done when
- After reattaching to a live session from the initially-active tab path, the client sends
  (or buffers and flushes on socket open) a `'1'`-tagged resize carrying the fitted size,
  even when that size equals xterm's constructor defaults — asserted by a component or
  session spec.
- Existing specs still pass: `cd client && npm test` (headless Chrome) is green, and
  `./mvnw -B test` from the repo root is green.
- Manual check (human judgment): with a Claude CLI console open at one window size, reload
  the browser at a different window size — the CLI redraws at the new width without a
  manual window resize.

## Explicitly not
- No engine change: `TerminalWebSocketHandler` and `SessionRegistry`/`PtySession` already
  handle resize messages correctly; ignoring URL size on reattach stays by design.
- No reconnect-on-close logic for a dropped WebSocket.
- No change to #117's debounced ResizeObserver refit or #130's focus notifications.

## Decisions made along the way
- Fixed entirely inside `TerminalSession`, not `TerminalComponent` (Claude, 2026-08-28).
  `TerminalSession`'s constructor already receives `cols`/`rows` (the just-fitted size for
  an initially-active tab, since `fitAddon.fit()` runs before `connect()` in the deferred
  callback) and `initiallyFocused` (which is `this.active` at both of the component's call
  sites — true only when the tab was visible, and therefore fitted, at connect time).
  Seeding `pendingResize` from `cols`/`rows` in the constructor when `initiallyFocused` is
  true delivers the size on socket open via the existing #268 flush-on-open path,
  independent of whether xterm's `onResize` event ever fires or whether `session` existed
  when it did — covering both gaps described in the issue without touching
  `terminal.component.ts` at all.
- Gated on `initiallyFocused` rather than seeding unconditionally whenever `cols`/`rows`
  are given (Claude, 2026-08-28). A tab that starts inactive also passes `cols`/`rows` into
  the constructor (xterm's un-fitted 80x24 defaults, from `this.term?.cols` before
  `term.open()`/`fitAddon.fit()` have run for that path), and unconditionally queuing those
  as a resize would force a reattaching *inactive* tab's already-correctly-sized PTY down
  to 80x24 immediately on connect — a regression #268 deliberately avoided (its own note:
  "its real size arrives via the buffered resize at first activation"). `initiallyFocused`
  is exactly the existing signal for "this tab was visible, and therefore fitted, when it
  connected", so reusing it (rather than adding a new constructor parameter) draws the line
  in the right place without new state.
- No `TerminalComponent` spec added (Claude, 2026-08-28). The issue's Done-when accepts
  either "a component or session spec"; #268 already noted standing one up needs a real DOM
  and canvas and is its own piece of work. Since the fix moved entirely into
  `TerminalSession` and the component already passed the right values into its
  constructor, the behavior is fully exercisable — and was exercised — at the session
  level, so no component-level test was needed to cover it.

## Deviations / notes
- none
