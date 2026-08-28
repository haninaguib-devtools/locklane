# 266 — Enlarge header logo without increasing header height

Issue: #266

## Asked
In the app header, the brand logo reads smaller than the other header elements sitting
next to it. Increase the logo's rendered size so it feels proportionate to its
neighbours, without making the header itself any taller.

## Done when
- The logo (`client/src/app/app.component.html`, `.brand-logo` img, previously
  `width="24" height="24"`) is enlarged — target matching the header's other elements
  (the 28px user avatar is the current tallest control), up to 28x28.
- `.topbar`'s padding (`client/src/app/app.component.css`) is unchanged, and the header's
  overall rendered height does not increase.
- Any spacing/alignment in `.brand-group`/`.brand-logo` is adjusted as needed so the
  larger logo sits correctly next to the "LockLane" wordmark.
- Verified visually in the running app (before/after), not just by reading the CSS.

## Explicitly not
- No redesign of the header layout, brand wordmark, or other header elements (console
  indicator, avatar) beyond spacing needed to fit the larger logo.
- No change to the logo asset itself (`client/public/logo.svg`).

## Decisions made along the way
- Took the logo to the full 28x28 the issue allows, rather than an intermediate size:
  the header's own content box is 30px tall (set by the "+ add project" button), and the
  brand wordmark's line box is 28.5px, so 28px is the largest size that still reads as
  subordinate to the wordmark while leaving the header height untouched (Hani,
  2026-08-28).
- Left `.brand-group`'s 9px gap alone. The mark is inset ~5/24 of its box on the right,
  so growing 24px → 28px widens the optical gap by ~0.8px — below the threshold worth a
  CSS change, and the rendered comparison shows the spacing still reading correctly
  (Hani, 2026-08-28).

## Deviations / notes
- Visual verification was done by rendering the real header markup against the real
  stylesheets (`client/src/styles.css`, `app.component.css`,
  `console-indicator.component.css`) in headless Chrome at 1280px, before and after, and
  measuring the boxes — not by driving the authenticated app, which needs a live login
  plus a TOTP code this session does not have. Measured: `.topbar` height is **63.00px
  in both** renders; `.brand-logo` goes 24.00 → 28.00px; every other header element
  (`.brand` 28.5, `.badge` 28, `.add-project` 30, `.avatar` 28) is unmoved. A human's
  eyeball pass in the signed-in app is still worth doing at review.
