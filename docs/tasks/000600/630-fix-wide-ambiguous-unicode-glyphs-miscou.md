# 630 — Fix wide/ambiguous unicode glyphs miscounted by terminal column width
Issue: #630

## Asked
The console's embedded terminal (xterm.js) miscounts the display width of special
unicode glyphs — the CLI's status/prompt line uses symbols like `▸▸`, `←`, `❚` whose
width (1 vs 2 columns) is ambiguous under xterm.js's legacy bundled character-width
table, since no `@xterm/addon-unicode11` (or newer) is loaded and
`term.unicode.activeVersion` is never set. This causes that line specifically to wrap
in the wrong place and show stray/garbled characters near the right edge of the
terminal panel, distinct from the pixel-rounding bug already fixed in #617 (which only
corrected glyph *positioning*, not column *counting*).

## Done when
- `@xterm/addon-unicode11` (or a newer unicode-width addon) is added as a client
  dependency and loaded in
  `client/src/app/components/terminal/terminal.component.ts` alongside the existing
  `FitAddon`/`ClipboardAddon`/`WebglAddon` setup.
- `term.unicode.activeVersion` is set so glyph width matches what the Claude CLI
  assumes when it wraps/pads that line to the PTY's column count.
- Manual verification: the CLI's status/prompt line (containing `▸▸`, `←`, `❚`) renders
  and wraps correctly at several terminal widths, with no stray characters near the
  right edge.

## Explicitly not
- No change to the fit/resize logic, the WebGL renderer, or the PTY size protocol.
- Nothing outside `TerminalComponent` and its client dependencies.

## Decisions made along the way
- `@xterm/addon-unicode11@0.9.0` is the version pinned: it was published the same
  minute as `@xterm/xterm@6.0.0` (2026-08-30T20:13:0{1,36}) — the paired stable
  release, matching the pinning approach #616 used for `@xterm/addon-webgl`. Its
  `latest` npm dist-tag already points at it; a `0.10.0-beta.*` line exists but is
  pre-release. (claude, 2026-09-02)
- Loaded eagerly (not dynamically imported like the WebGL addon): it is a small,
  synchronous addon with no GPU/context dependency, so there is no bundle-size or
  context-loss concern motivating a deferred load. (claude, 2026-09-02)

## Deviations / notes
- none
