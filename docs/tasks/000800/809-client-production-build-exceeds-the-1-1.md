# 809 — Client production build exceeds the 1.1 MB initial-bundle budget
Issue: #809

## Asked
Every build on `main` fails before a single engine test runs: the Angular client's
production build stops with `bundle initial exceeded maximum budget. Budget 1.10 MB was
not met by 346 bytes`. Nothing the user sees is broken, but CI is red on `main` and every
open PR inherits it. Two things fix it, both decided by the owner on 2026-09-07:

1. Lazy-load the markdown renderer (`marked` + `dompurify`) from `OverviewTabComponent`
   via a dynamic import, the pattern `TerminalComponent` already uses for the xterm WebGL
   addon, moving ~73 KB out of the initial bundle. The body is rendered once per issue
   change in `ngOnChanges` instead of on every change-detection pass.
2. Double both `initial` budget thresholds in `client/angular.json`:
   `maximumWarning` 500 kB → 1 MB, `maximumError` 1.1 MB → 2.2 MB.

## Done when
- From a clean checkout of the branch, `./mvnw -B test` completes with `BUILD SUCCESS`
  (client builds and its unit tests pass; engine suite runs and passes, including
  `SpaFallbackControllerTest`).
- CI's `checks` job is green on the task's PR.
- The production build reports `marked`/`dompurify` in a lazy chunk, not in `main`, and
  the initial total is below the old 1.1 MB line as well as the new one.
- `client/angular.json`'s `initial` budget reads `maximumWarning: 1MB`,
  `maximumError: 2.2MB`; the `anyComponentStyle` budget is untouched.
- This record's Decisions section says what the thresholds were raised to, why, and
  roughly what accounts for the growth since #541's 1.1 MB.
- No behaviour change: the Overview tab's rendered issue body is the same HTML,
  sanitized the same way; it appears after the lazy chunk loads.

## Explicitly not
- No engine change.
- Not a general bundle-size audit; xterm and its addons stay in the initial bundle.
- No dependency added or removed.
- The pre-existing `sidenav.component.css` style-budget warning is left alone.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- **Thresholds doubled to `maximumWarning: 1MB`, `maximumError: 2.2MB`** (owner,
  2026-09-07). Measured on `main` at 1839dca, the 1.06 MB main chunk was: 345 KB
  `@xterm/xterm`, 243 KB app code under `src/`, 321 KB Angular framework (core, router,
  cdk, forms, common, platform-browser), 73 KB `marked` + `dompurify`, 30 KB
  `@xterm/addon-unicode11`, 45 KB rxjs and the rest. Nothing new and heavy arrived since
  #541 raised the ceiling to 1.1 MB: the growth is 40 feature tasks (#574 … #799, about
  6,900 lines added under `client/src`) of ordinary app code, plus the unicode11 addon
  (#630). Lazy-loading the markdown renderer alone buys ~7 % headroom; doubling the
  ceiling stops the budget from failing `main` every few dozen feature tasks. The
  `anyComponentStyle` budget is left as it was.
- **Render once per issue, not per change-detection pass** (agent, 2026-09-07). The
  dynamic import forces the render to be asynchronous, so the synchronous `bodyHtml`
  getter becomes a field set from `ngOnChanges`. A side benefit: the old getter
  re-parsed and re-sanitized the whole body on every change-detection pass.

## Deviations / notes
- The issue as first written said the `maximumWarning` threshold stays as it is; the
  owner changed that on 2026-09-07 (double both thresholds) and the issue body was
  updated to match before this work started.
