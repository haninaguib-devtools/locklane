# 616 — Fix right-edge glyph clipping in wide consoles by rendering with xterm's WebGL addon
Issue: #616

## Asked
On a wide browser window the console cuts off the last character of long lines: xterm's
DOM renderer measures cell width from an integer `offsetWidth`, and that rounding error
accumulates across a wide row (~265 columns) until the row's `overflow: hidden` box
clips part of the last glyph. Fix it by rendering every console tab's terminal with
xterm's WebGL renderer (`@xterm/addon-webgl`), which draws glyphs on a canvas at exact
cell positions instead of measuring and rounding. Handle `onContextLoss` by disposing
the addon so a tab falls back to the DOM renderer instead of going blank.

## Done when
- `client/package.json` and `client/package-lock.json` carry `@xterm/addon-webgl` at a
  version compatible with `@xterm/xterm` 6.0.0, and
  `grep -c "addon-webgl" client/src/app/components/terminal/terminal.component.ts` is
  ≥ 1.
- `TerminalComponent` loads the WebGL addon after `term.open()`, registers an
  `onContextLoss` handler that disposes the addon (falling back to the DOM renderer),
  and disposes the addon in `ngOnDestroy`; a unit test in `terminal.component.spec.ts`
  covers the context-loss fallback.
- Human check: in a console tab in a window wide enough for 200+ columns, a full-width
  line of agent output shows its last character whole, and
  `document.querySelector('.xterm-screen canvas')` exists on the console page.
- Human check: switching tabs, closing a tab, and reattaching after a reload behave as
  before; no console tab renders blank.
- `./mvnw -B test` passes (the client build is a build input) and
  `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No change to the fit/resize logic, the debounce, the tab layout, or the PTY size
  protocol.
- No font-size or letter-spacing workaround.
- Nothing outside `TerminalComponent`.

## Decisions made along the way
- `@xterm/addon-webgl@0.19.0` is the version pinned: it was published the same minute as
  `@xterm/xterm@6.0.0` (2025-12-22T13:5{0,1}), i.e. the paired stable release from the
  xterm.js monorepo — the `latest` npm dist-tag happens to point at it already. Newer
  `0.20.0-beta.*` versions exist but are pre-release. (claude, 2026-09-02)
- The addon's `activate()` (invoked by `Terminal.loadAddon()`) throws synchronously
  `"WebGL2 not supported"` when the browser/environment lacks a WebGL2 context — this is
  the addon's own documented failure mode, not a hypothetical: wrapping the initial
  `loadAddon(webglAddon)` call in try/catch falls back to the DOM renderer the same way
  `onContextLoss` does for a context lost after the fact, so an unsupported browser (or
  a test/CI environment without GPU/software WebGL2) never blanks a console tab.
  (claude, 2026-09-02)

## Deviations / notes
- none
