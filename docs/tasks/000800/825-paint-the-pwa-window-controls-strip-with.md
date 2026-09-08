# 825 — Paint the PWA window-controls strip with the Appearance accent's soft tint

Issue: #825

## Asked
When Locklane runs as an installed desktop app (PWA) with the window-controls overlay
active, the strip above the header — the area the OS draws its close/minimize buttons
into — is painted a fixed sidebar colour today. This task makes that strip take the
soft tint of the accent colour chosen in Settings → Appearance instead, in both the
main window and the separate Shells window, and keeps the browser's `theme-color` in
step so the strip does not flash sidebar-coloured before the page paints. The point is
to let a person running two installs (a local instance and a remote one) tell the
windows apart at a glance by choosing a different accent on each. The project's own
accent colour keeps controlling the header row's background exactly as before; nothing
changes in a normal browser tab, where the address bar already distinguishes instances.

## Done when
- In `client/src/app/app.component.css`, the `.wco-active .topbar` gradient's opaque
  stop is `var(--accent-soft)` and no longer `var(--sidebar)`.
- In `client/src/app/components/shells-window/shells-window.component.css`,
  `.window.wco-active .titlebar` sets `background: var(--accent-soft)`; the default
  (non-overlay) `.titlebar` rule still has height 0 and no background.
- `AccentThemeStore.apply()` (or an equivalent called from the same two places:
  constructor and `choose()`) also sets the document's `<meta name="theme-color">`
  `content` to the preset's `accentSoft`, so the strip is the right colour from first
  paint and switches when the preset changes. The static `content` in
  `client/src/index.html` and `theme_color` in `client/public/manifest.webmanifest` may
  stay as the default preset's soft tint (`#f7e9e2`) or remain `#f2f0eb`; the
  implementer picks one and states it here.
- A unit test in `client/src/app/services/accent-theme-store.spec.ts` asserts that
  choosing a preset updates the `theme-color` meta content to that preset's
  `accentSoft`, and that the constructor applies it for the stored preset.
- Existing client tests still pass and the checks in `AGENTS.md` §Checks pass.
- Human check (only a person can judge): in an installed PWA window with the overlay
  active, the strip above the header is the chosen accent's soft tint in both the main
  window and the Shells window, changes immediately when a different accent is picked
  in Settings, and shows no sidebar-coloured flash on reload. A normal browser tab
  looks unchanged.

## Explicitly not
- No change to the project accent colour or to how the header row (`.topbar`) derives
  its background from it (`project-accent-tint.ts` untouched).
- No change to the Appearance section's wording or to how accent colours are selected —
  colour selection is being reworked in a separate future issue.
- No new preset fields, no free colour input, no server-side persistence.
- No behaviour change in a normal browser tab or in a window without the overlay.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Kept `client/src/index.html`'s static `theme-color` and `manifest.webmanifest`'s
  `theme_color` at `#f2f0eb` (the sidebar colour, unchanged) rather than switching them
  to the default preset's `accentSoft` (`#f7e9e2`) — the meta tag's `content` is
  overwritten by `AccentThemeStore`'s constructor before first paint in every case that
  matters (an installed PWA with JS running), so the static value only shows in the
  brief window before JS executes or in contexts where it never runs (e.g. crawlers) —
  matching the manifest's `background_color`, which stays the neutral window colour,
  seemed the more conservative choice. (Claude, 2026-09-08)

## Deviations / notes
none
