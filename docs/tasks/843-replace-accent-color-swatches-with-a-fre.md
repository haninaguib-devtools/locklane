# 843 — Replace accent color swatches with a free color picker and a reset to default
Issue: #843

## Asked
Today the accent color is chosen from four fixed swatches (terracotta, sage, slate, plum) in two places: the Appearance section of the Settings dialog, which sets the app-wide accent stored client-side in localStorage, and the "accent color" row on a project's summary page, which stores a hex color on the project via `PUT /api/projects/{id}/accent-color`. Both draw from the shared `ACCENT_PRESETS` table in `client/src/app/services/accent-theme-store.ts`, whose twin lives in the inline bootstrap script in `client/src/index.html`. Replace both swatch rows with a free color picker (a native `<input type="color">`) plus a "Reset" control that returns to the default. For the app-wide accent, the default is the built-in terracotta from `styles.css` with nothing stored; the soft companion tint (`--accent-soft`, and the PWA `theme-color` meta) is derived from the chosen hex with the same 13% blend-with-white that `deriveProjectBackgroundTint` already uses, both in the store and in the `index.html` bootstrap script, so the hand-picked preset table can go. For a project, the default is no color set (`accent_color` NULL, which the topbar already renders as no tint), so the backend must gain a way to clear the color — accept `{"accentColor": null}` on the existing PUT, or a DELETE on the same path — and the project page's reset must call it. Ownership gating on the endpoint stays exactly as it is.

## Done when
- The Settings dialog's Appearance section shows one color input and a Reset button, and no preset swatches; choosing a color applies `--accent` and a derived `--accent-soft` to the document root and updates the `theme-color` meta; Reset restores the terracotta default and removes the localStorage entry.
- On reload with a stored custom color, the accent and `theme-color` meta are correct on first paint via the `index.html` bootstrap script (no preset table remains there).
- A project's summary page shows one color input and a Reset button; choosing a color persists it through the projects service; Reset persists NULL and the page and topbar drop the tint.
- The engine accepts the clear operation for the project's owner (returns 204 and the stored `accent_color` becomes NULL), still rejects a malformed hex with 400, and still returns 404 for a project the caller does not own.
- `grep -rn "ACCENT_PRESETS" client/src` finds nothing.
- `./mvnw -B test` passes, including the updated specs for the store, tint helper, both components, the projects service, and `ProjectControllerTest`.

## Explicitly not
- No change to the `owner_user_id` authorization checks in `ProjectController` or `ProjectRepository`, nor to any Flyway migration; the `accent_color` column already allows NULL.
- No server-side storage of the app-wide accent; it stays client-only in localStorage.
- No change to the localStorage key `locklane.accentTheme` or the REST path (compatibility surfaces); the stored value's shape may change from a preset id to a hex string, with an unrecognised stored value treated as the default.
- No contrast or accessibility validation of the chosen color beyond what the native picker provides.

## Decisions made along the way
- Chose the PUT-with-`{"accentColor": null}` option (over a separate DELETE) to clear a
  project's accent color, since the endpoint's `SetAccentColorRequest` already carries a
  nullable `accentColor` field and the repository's `setAccentColor` already accepted
  `null` (only the controller was rejecting it with a 400).
- `AccentThemeStore`'s derived `accentSoft` reuses `deriveProjectBackgroundTint` directly
  (both take a raw hex, and the store's chosen/default accent is always a valid 6-digit
  hex) instead of re-implementing the 13% blend — one function, not two copies of the
  same math. `index.html`'s bootstrap script still carries its own copy of the blend
  arithmetic by hand, same as before, since it must run standalone before any app bundle
  exists to import from.
- Left `CHANGELOG.md` untouched even though it's in the issue's Scope: its own header and
  `docs/architecture/releasing.md` both establish it is generated only by the release-cut
  task (`scripts/generate-release-notes.sh`) from squash-merge commit messages, never
  edited by an ordinary task PR — confirmed against this repo's actual history, where
  every past edit to this file came from a "Cut release" commit.

## Deviations / notes
- `client/src/app/app.component.spec.ts` (outside the issue's Scope) had one test —
  "picking an accent color from the project summary tints the topbar immediately" —
  that drove the old `.accent-swatch` buttons directly; updated it to drive the new
  `.accent-color-input` instead, since removing the swatches broke it. No other change
  to that file.
