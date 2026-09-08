# 836 — Keep the PWA window-controls strip tinted across reloads and under the release banner
Issue: #836

## Asked
In an installed desktop app window (PWA with the window-controls overlay active), the
strip above the header is meant to be the soft tint of the accent chosen in
Settings → Appearance (#825), so two installs can be told apart at a glance. Two things
break that today. First, a page reload puts the strip back to its default sidebar
colour, even though the chosen accent survives the reload everywhere else. Second, when
the "A newer version (x.y.z) is available" banner is showing, it sits inside that strip
with its own grey background, covering the tint. After this task the strip shows the
chosen accent's soft tint at all times in an installed window — after a reload, with no
default-coloured flash, and with the release banner showing through on top of the tint
rather than replacing it.

## Done when
- `client/src/index.html` carries a small inline script in `<head>`, before any
  stylesheet or app bundle, that reads the stored preset id from localStorage key
  `locklane.accentTheme`, resolves it against the same four presets `AccentThemeStore`
  knows (falling back to the default preset, terracotta, when the key is missing or
  unknown, and swallowing a storage exception), and sets both `--accent` /
  `--accent-soft` on `document.documentElement` and the `theme-color` meta `content` to
  that preset's soft tint. A grep for `locklane.accentTheme` in `client/src/index.html`
  matches. The preset table is either shared with `accent-theme-store.ts` or duplicated
  with a comment on both sides naming the other as the twin; the implementer states
  which in the record.
- `AccentThemeStore` still applies the preset on construction and on `choose()`
  (existing unit tests keep passing), so a preset picked in Settings still switches the
  strip live.
- `client/public/manifest.webmanifest`'s `theme_color` is the default preset's soft
  tint (`#f7e9e2`), and the static `content` of the `theme-color` meta in `index.html`
  matches it, so a fresh install with no stored choice matches the default preset too.
- Under the overlay, the accent tint is painted by something that spans the whole strip
  regardless of what sits in it — for example the `.wco-active` container rather than
  only the header's gradient — and the release banner's background is transparent when
  it sits in the strip, so the banner's text reads over the tint rather than over its
  own grey box. The banner's readability (text colour, border) is preserved; in a
  normal browser tab or an overlay-disabled window the banner looks exactly as it does
  today (`var(--border-soft)` background).
- The strip's height accounting stays correct when the banner is present under the
  overlay: the header does not reserve a second strip-height of padding below a banner
  that already fills the strip, and nothing overlaps the OS controls region
  (`env(titlebar-area-*)`). Verified by the human check below.
- Existing client tests pass; the checks in `AGENTS.md` §Checks pass.
- Human check (only a person can judge): in an installed PWA with the overlay active,
  (a) reload the main window and the Shells window with a non-default preset chosen —
  the strip, including the region around the OS buttons, is that preset's soft tint
  immediately, with no sidebar-coloured flash; (b) with the release banner showing, the
  strip is still the tint and the banner text reads over it; (c) a normal browser tab
  looks unchanged.

## Explicitly not
- No change to the project accent colour or to how the header row derives its
  background from it.
- No change to the Appearance section's wording or selection UI, no new presets, no
  free colour input, no server-side persistence.
- No change to the bottom-of-window "A new version is available" reload toast
  (`update-banner`).
- No behaviour change in a normal browser tab or in a window without the overlay,
  beyond the manifest/meta defaults now matching the default preset.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- none

## Deviations / notes
- none
