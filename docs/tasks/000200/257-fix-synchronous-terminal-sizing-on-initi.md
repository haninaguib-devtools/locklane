# 257 — Fix synchronous terminal sizing on initial console mount
Issue: #257

## Asked
The project console page (and the issue page, which reuses the same `TerminalComponent`)
still shows a misaligned terminal for the console tab that is selected when the page
first loads. Issue #211 fixed this for the *tab-switch* case: `ngOnChanges` defers
`fitAddon.fit()` (and `term.focus()`) with `setTimeout` so it measures the container only
once it is actually visible. The *initial-mount* case never got the same treatment:
`ngAfterViewInit` calls `term.open()` and `fitAddon.fit()` synchronously the instant the
component initializes, with no deferral. xterm.js caches whatever character-cell size it
sees at `open()` time, and a later `fit()` against a bad cached size does not correct it.
Most of the time the container already has its final layout size by then, but when that
assumption doesn't hold (e.g. the console page's own change-detection pass hasn't
settled), the initially-active console locks in a 0-size/misaligned grid, reproducing the
exact symptom #211 addressed.

Bring the initial-mount path in line with the tab-switch path: defer the first
`fitAddon.fit()` call the same way `ngOnChanges` does, so both "a console becomes
visible" code paths measure the container on the same, already-proven-safe schedule.

## Done when
- The initially-selected/active console tab, in both the project console page and the
  issue page, renders correctly sized and aligned text on first load, including when the
  page's initial layout has not fully settled by `ngAfterViewInit`.
- `ngAfterViewInit`'s `term.open()` / `fitAddon.fit()` sequence for an initially-active
  tab uses the same deferred (not synchronous) measurement pattern already used in
  `ngOnChanges` for tab switches.
- Existing behavior for tab switching (#211) and for an initially-inactive tab's first
  activation is unchanged.

## Explicitly not
- No spec file exists yet for `TerminalComponent`; none is added — out of scope per the
  issue ("and its spec, if one exists").

## Decisions made along the way
- none

## Deviations / notes
- none
