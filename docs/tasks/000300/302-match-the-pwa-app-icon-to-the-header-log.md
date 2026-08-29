# 302 — Match the PWA app icon to the header logo
Issue: #302

## Asked
The icon shown when LockLane is installed as an app (home screen icon on iOS/Android,
taskbar/dock icon on desktop) doesn't match the padlock logo shown in the app's own
header. The installed-app icon set instead showed a plain "L" monogram on the same
orange background. Regenerate the app icon set from the header's padlock mark so a user
sees the same logo whether they're looking at the browser tab/header or the icon on
their home screen/dock.

## Done when
- `client/public/icons/icon-192.png`, `client/public/icons/icon-512.png`, and
  `client/public/apple-touch-icon.png` are regenerated to depict the padlock mark from
  `client/public/logo.svg` (not the "L" monogram), each still meeting the size/format
  its consumer requires (192x192 and 512x512 PNG per `manifest.webmanifest`'s existing
  `any maskable` entries; 180x180 PNG for the Apple touch icon)
- `client/public/favicon.ico` likewise reflects the padlock mark
- `manifest.webmanifest`'s icon entries still reference valid files/sizes/purposes after
  regeneration (edited only if the regeneration changes dimensions or filenames)
- Loading the app in a browser and checking Chrome DevTools' "Installability" /
  Application panel still shows a valid manifest, and the icon preview shows the padlock
  mark
- Visual check (side-by-side of the header logo and the new icons) confirms they read as
  the same mark

## Explicitly not
- Changing the header logo (`client/public/logo.svg`) itself, or the brand colors/theme
  (`manifest.webmanifest`'s `theme_color`/`background_color`).
- Any other PWA installability work — manifest fields, service worker, TLS — already
  covered or deferred by #222.

## Decisions made along the way
- The padlock's shackle, keyhole circle, and body were all recolored solid white
  (matching the previous white-on-orange "L" monogram's contrast scheme) and composited
  on the existing `#c15f3c` background, since rendering `logo.svg`'s own two-tone
  colors (dark shackle, orange body) directly onto an orange icon background would make
  the body disappear into the background (haninaguib, 2026-08-28).
- Regenerated with `librsvg`/`cairo` (via PyGObject) + Pillow, already present on the
  system, rather than adding a new client build dependency — this is a one-off asset
  regeneration, not a repeatable build step (haninaguib, 2026-08-28).
- All four output files kept their existing dimensions and format (192x192, 512x512,
  180x180 PNG; 16/32/48 multi-size ICO), so `manifest.webmanifest` needed no edit.

## Deviations / notes
- none
