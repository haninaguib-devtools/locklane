# 376 — Re-send the terminal's current size on every console reconnect
Issue: #376 · Part of: #374

## Asked
`TerminalSession` (`client/src/app/services/terminal-session.ts`) treats the terminal's
size as an edge rather than as state. Its `cols`/`rows` are readonly constructor
arguments that it puts on the connect URL of *every* later reconnect, and its
`pendingResize` slot is seeded once in the constructor and consumed on the first socket
open. The only other channel is `TerminalComponent`'s `term.onResize` handler, which
fires only when xterm's own size *changes* — and FitAddon's `fit()` is a no-op when the
size it proposes already matches.

So after any reconnect the client asserts no size at all, and the URL advertises whatever
size the terminal had when its component was constructed. #279's heartbeat closes idle
and backgrounded connections routinely, so this path runs often. When the engine has to
create the PTY on that attach — after an engine restart, where `SessionRegistry.attach`
finds nothing in its in-memory map while the session row is still persisted and #173
relaunches the tool with `--resume` — the new PTY starts at that stale URL size and
nothing ever corrects it, because xterm's size never changes again. The long-standing
workaround of dragging the sidenav separator worked precisely because it forced a size
change, which is the only thing that emits a resize.

Make the size level-triggered instead: hold the terminal's current size, update it on
every `resize()` call, put it on every connect URL, and send it on every socket open
rather than only the first.

## Done when
- After a socket drops and reconnects, the engine receives the terminal's current size —
  not the size the session was constructed with, and not nothing.
- Restarting the engine under a mounted console tab and then returning to that tab leaves
  the reattached process at the browser terminal's real size, with no manual resize
  needed.
- A reconnect still resends the focus notification (#130), and a `resize()` that races a
  reconnect is still honoured rather than lost.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- The `display: none` measurement hole that makes the frozen size 80x24 in the first
  place — sibling task #375 under this initiative, merged into this initiative's
  integration branch before this task branched from it.
- Changing #279's reconnect/backoff behaviour or the engine's heartbeat.
- Any engine change.

## Decisions made along the way
- Dropped the `pendingResize` slot entirely rather than keeping it alongside the held
  size (implementing session, 2026-08-29). Once the size is state that every open
  re-asserts, a one-shot queue for "a size that arrived before the socket opened" has nothing left to
  do: `resize()` records the size and the next open sends whatever is current. Keeping
  both would have meant two places holding the same fact, which is the shape the bug
  came from.
- Kept the `initiallyFocused` constructor parameter instead of removing it
  (implementing session, 2026-08-29). #375's Goal describes it as collapsing, but only its *size* half was
  special-casing — it also decides whether a focus notification goes out on open (#130),
  which is still needed. Its size half is gone and its doc comment now says so, so the
  call site in `terminal.component.ts` is unchanged and the two tasks stay off each
  other's files.

## Deviations / notes
- **Two existing specs were inverted rather than left passing, and this is the thing to
  look at in review.** Both encoded the edge-triggered rule this task exists to remove,
  so neither could survive the change:
  - `'holds no further size once a pending resize has been delivered (#268)'` asserted
    that a held size is consumed on the first open and never sent again. That one-shot
    consumption *is* the defect. Replaced by `'re-sends the size it currently holds on
    every open, not only the first (#376)'` — same scenario, opposite expectation.
  - `'does not invent a resize at connect for a tab that started inactive (#271)'`
    asserted silence for an inactive tab. Under #271 that was right: an inactive tab
    could not be measured, so its 80x24 was xterm's unfitted default and asserting it
    would have been a lie. Since #375 every tab is fitted before it connects, so an
    inactive tab's size is a real measurement and withholding it is what leaves a
    reattached PTY wrong. Replaced by `'sends the connect-time size even for a tab that
    started inactive (#376)'`.

  Both replacements carry a comment naming the spec they replace and why the reason
  changed underneath it, so the history is readable at the point of change and not only
  here.
- The connect URL check moved from truthiness (`if (this.cols)`) to an explicit null
  check. No behaviour change for any real size, but the held size is mutable state now
  and `0` should not silently mean "unknown".
- A brand-new session now receives its size twice on connect — once on the URL, once as
  the `'1'`-tagged message right after open. That was already true for a focused tab
  under #271; it is now true for every tab. Left as-is: the engine ignores the URL size
  whenever it finds a live session, so the message is the only reliable channel, and
  suppressing the duplicate would need the client to know which side the engine took.
- Not verified in a browser: both of this task's first two Done-when criteria need a
  running engine (one of them needs the engine restarted underneath a live tab). The
  automated specs cover the client-side rule — current size on the reconnect URL, the
  resize message re-sent on every open, a racing `resize()` honoured — but the
  end-to-end claim about the reattached process is a human check.
