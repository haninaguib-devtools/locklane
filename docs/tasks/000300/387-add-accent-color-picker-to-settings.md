# 387 — Add accent color picker to settings
Issue: #387

## Asked
Let users personalize the console's look by choosing the UI's accent color from a small
set of preset colors, picked from a new "Appearance" section in the settings dialog.
Today the accent color (the terracotta/orange-brown used for buttons, selections, and
highlights) is hardcoded.

## Done when
- The settings dialog has a new "Appearance" section with 4 clickable color swatches:
  Terracotta `#c15f3c` (current default), Sage `#5c8a4e`, Slate `#4d6a8a`, Plum `#8a5568`.
- Selecting a swatch updates the app's `--accent` and `--accent-soft` CSS custom
  properties live, so buttons, selected states, and highlights across the app reflect
  the choice immediately.
- The choice persists across page reloads (e.g. `localStorage`) and is restored on next
  visit.
- A user who has never chosen sees Terracotta, unchanged from today's behavior.
- Existing settings dialog sections (default agent, password, 2FA) are unaffected.

## Explicitly not
- A custom/arbitrary color picker (hex input) — only the 4 presets above.
- Server-side or cross-device persistence of the choice — local to the browser only.
- A separate light/dark theme toggle.

## Decisions made along the way
- none

## Deviations / notes
- Touched `client/src/app/app.component.ts` (one field) in addition to the issue's
  named scope: an Angular `providedIn: 'root'` service only constructs on first
  injection, so without an eager inject somewhere always-alive, the stored accent
  would not apply to `:root` until the settings dialog was opened once. `AppComponent`
  is the natural always-alive root; the change is one line plus a comment explaining
  why the field looks unused.
- `--accent-soft` for Slate and Plum has no existing counterpart to reuse (Terracotta
  and Sage's soft tints already exist in `styles.css`, Sage's accent being identical to
  the existing `--green`). Computed new soft tints at the same ~13% blend-with-white
  ratio the existing accent/soft pairs use, documented in `accent-theme-store.ts`.
