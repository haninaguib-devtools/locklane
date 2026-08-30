# 375 — Keep a hidden console tab laid out so it mounts at its real terminal size
Issue: #375 · Part of: #374

## Asked
An unselected console tab is hidden with `display: none` (`app-terminal.tab-hidden`,
defined in `main-content.component.css` and `project-console.component.css`). An element
inside a `display:none` subtree has no layout box, so it cannot be measured: on the
versions this repo pins (`@xterm/xterm` 6.0.0, `@xterm/addon-fit` 0.11.0),
`getComputedStyle()` on the terminal's container returns `width: "auto"` /
`height: "auto"`, FitAddon's `proposeDimensions()` returns `{cols: NaN, rows: NaN}`, and
`fit()` returns without resizing. `TerminalComponent` therefore cannot size a tab that
mounts while hidden, and connects it at xterm's 80x24 constructor default.

That is not a timing problem, which is why deferring `term.open()` to first activation
(#211), the synchronous-sizing fix (#257) and sending the real size at connect (#268) all
left it in place. The damage is done before the user ever clicks the tab: the engine
replays the session's whole buffered output to every attach, so bytes a CLI drew at the
real width are parsed by an 80-column terminal. Resizing on activation cannot repair it —
xterm's reflow only rejoins soft-wrapped lines, while absolute cursor positioning was
already clamped to column 80.

Give a hidden tab a layout box instead of removing it, and open and fit every terminal at
mount whether or not it is the selected tab. A hidden tab then measures correctly,
connects at its real size, and picks up window resizes that happen while it is hidden;
the `opened` / `pendingInit` / `initiallyFocused` special-casing in `TerminalComponent`
and `TerminalSession` collapses along with it.

## Done when
- A console tab that mounts while not selected reports its real terminal size rather than
  80x24: with two consoles open and console B selected, navigating away and back and then
  selecting console A leaves A's scrollback identical to the same bytes written to a
  console that was visible throughout.
- The connect URL for a tab that mounts hidden carries that tab's fitted `cols`/`rows`,
  not 80 and 24.
- A window resize that happens while a console tab is hidden is reflected in that tab's
  size without waiting for it to be selected.
- Switching between console tabs still never drops a connection or its scrollback (#30),
  and a hidden tab still receives and buffers output.
- The terminal still receives keyboard focus on open and on tab switch (#166).
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Re-asserting the size on reconnect — sibling task #376 under this initiative.
- Any engine change.

## Decisions made along the way
- Hid the inactive tab with `position: absolute; inset: 0; visibility: hidden` inside a
  positioning wrapper rather than with `content-visibility: hidden` or an off-screen
  transform (hani, 2026-08-29). `content-visibility: hidden` skips the subtree's layout
  too, so the container inside it measures 0 and the bug survives; a transform keeps the
  element in flex flow, so every hidden tab would still divide the free space with the
  visible one.
- Kept the terminal rendering while hidden rather than suppressing paint some other way
  (hani, 2026-08-29). The issue flagged this in advance: a `visibility: hidden` element
  still counts as intersecting, so xterm's IntersectionObserver render-pause will not
  engage for background tabs and they keep rendering. Not measured here — the issue asks
  for it to be measured before settling, and that measurement wants a running engine with
  a busy background console, which this task did not set up. Left as-is because every
  alternative that suppresses paint harder also removes the layout box the fix depends
  on; recorded as the open item on this task rather than silently accepted.

## Deviations / notes
- **Scope widened by two files, approved by hani in the moment (2026-08-29).** The issue's
  Scope named only `main-content.component.css` and `project-console.component.css` for
  the two parent components, assuming a CSS-only swap of `display: none` for
  `position: absolute; inset: 0`. That does not hold in this app: `inset` resolves against
  the nearest *positioned* ancestor and there is none — `app.component.css` positions
  neither `.authed` nor `.shell`, and neither parent host is positioned — so a hidden tab
  would have sized itself to the whole browser window. Positioning the parent host instead
  (which was in scope) still spans the issue header, flow strip and tab strip, because
  `app-terminal` is their flex sibling: a hidden tab would have fitted to roughly 9–15 rows
  more than it will actually have. Correct sizing needs a positioning context around the
  tab bodies alone, so `main-content.component.html` and `project-console.component.html`
  each gained one `<div class="tab-bodies">` wrapper. No bindings, control flow or
  component logic in those templates changed.
- `TerminalSession` was left untouched, though the issue's Goal names its
  `initiallyFocused` special-casing as collapsing along with `opened` / `pendingInit`.
  That file is the whole declared scope of sibling task #376, and editing it here would
  have put two children of #374 on the same file. The special-casing also cannot be
  removed safely on its own: `initiallyFocused` is what queues the first fitted size as a
  `'1'`-tagged resize, which is the only channel that reaches an already-running PTY,
  since the engine ignores connect-URL `cols`/`rows` on a reattach (#271). Dropping it
  before the size is level-triggered would regress that. #376 makes the size
  level-triggered and takes the collapse with it.
- Consequence, deliberate and bounded: on this branch alone, a tab that mounts hidden now
  connects at its real size on the URL (so a brand-new session is correct), but only the
  selected tab still queues the redundant resize, so a *hidden* tab reattaching to a live
  PTY asserts no size. That is the second half of the initiative and is #376's job; the
  two land together on `wip/374-integration`.
- The `setTimeout` in `ngOnChanges` is still not cancelled in `ngOnDestroy`, matching what
  was there before. Left as-is rather than fixed in passing — noticed, not in scope.
