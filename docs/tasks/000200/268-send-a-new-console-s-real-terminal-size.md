# 268 — Send a new console's real terminal size at connect instead of 80x24

Issue: #268

## Asked
A console opened in the browser starts its shell/agent process at the wrong size: the
program on the other end believes the window is 80 columns by 24 rows, whatever the
browser terminal actually measures. Long lines wrap early and full-width output (a
`claude` session's own rendering especially) is laid out for a window far narrower than
the one on screen. It stays wrong for the life of the session unless the user resizes the
browser window, which finally forces a correction through.

This is a regression from #257. The browser tells the engine a new session's size twice:
once as `cols`/`rows` query parameters on the WebSocket URL at connect time, and again as
a resize message whenever the size changes afterwards. #257 moved the first measurement
(`fitAddon.fit()` in `TerminalComponent.ngAfterViewInit`) into a `setTimeout` so it runs
after the current change-detection cycle — correctly fixing the misaligned first render —
but left the `this.connect()` call immediately below it synchronous. The connection is
therefore opened before anything has been measured, carrying xterm's constructor defaults
of 80x24, and the engine sizes the brand-new PTY to those.

The measurement does happen a moment later and does emit a resize, but that message is
dropped: `TerminalSession.sendFramed` silently discards anything sent while the socket is
not yet `OPEN`, and the handshake has not completed a tick after `connect()`. Nothing
retries, and no further resize is emitted on its own, because the *browser* terminal is
now correctly sized.

The same dropped-message hole affects a tab that starts inactive: it connects at 80x24 by
design and depends entirely on the resize emitted at its first activation arriving, which
today is a race against its own handshake.

Fix both halves: make a resize requested before the socket is open be delivered once it
opens, and make a brand-new session's connect carry the fitted size rather than the
pre-fit defaults.

## Done when
- Opening a console renders the terminal correctly sized *and* the process on the other
  end agrees: with a browser window wide enough for well over 80 columns, `tput cols` in
  a freshly opened shell console reports the browser terminal's actual column count, not
  80. (Human-judged: requires running the app.)
- A resize requested on a `TerminalSession` whose socket has not yet opened is delivered
  when it opens, rather than discarded — covered by a test in
  `client/src/app/services/terminal-session.spec.ts`. Only the most recent size need be
  delivered; superseded ones may be dropped.
- A brand-new session's WebSocket URL carries the fitted `cols`/`rows` for an
  initially-active tab, not xterm's 80x24 defaults.
- The initial-render fix from #257 is preserved: the first `fitAddon.fit()` for an
  initially-active tab still measures the container after layout has settled, not
  synchronously inside `ngAfterViewInit`.
- Tab-switch behavior from #211 (deferred open/fit/focus on first activation) is
  unchanged.
- `./mvnw -B test` passes, including the Angular suite.

## Explicitly not
- No change to the engine: `TerminalWebSocketHandler` and `PtySession` already accept both
  the query-parameter size and later resize messages correctly. The defect is entirely in
  what the client sends and when.
- No spec file is added for `TerminalComponent` — none exists yet, and standing one up
  (xterm needs a real DOM and a canvas) is its own piece of work, not a rider on this fix.
- No change to the debounced ResizeObserver refit path (#117's quiet period) or to how
  focus notifications (#130) are sent.

## Decisions made along the way
- The connect for an initially-active tab moves *inside* the existing deferred-fit
  `setTimeout` rather than into a second timer (Claude, 2026-08-28). One timer keeps the
  ordering explicit — measure, focus, then connect with what was measured — and leaves
  #257's "fit after layout settles" property literally unchanged rather than
  reconstructed. A tab that starts inactive keeps connecting synchronously, because it
  has nothing measurable to wait for; its real size arrives via the buffered resize at
  first activation.
- The pending size is stored as the already-framed `<cols>x<rows>` payload, single-slot,
  overwritten by each later request (Claude, 2026-08-28). The engine only ever needs the
  size the terminal actually ended at; replaying superseded sizes would just make the PTY
  reflow twice.
- `close()` does not clear the pending size (Claude, 2026-08-28). A `TerminalSession` is
  never reconnected — the component builds a new one — so a leftover slot on a closed
  session is unreachable, and adding the clear would imply a reconnect path that does not
  exist.

## Deviations / notes
- The deferred connect is now cancellable: `ngAfterViewInit`'s timer is held in a field
  and cleared in `ngOnDestroy`, alongside the existing `pendingFit` cleanup. Without it, a
  tab destroyed inside the first tick would open a WebSocket that nothing owns or closes.
  This is one line beyond the literal fix, in a file already in scope, and it exists only
  because the fix is what made that timer able to connect.
