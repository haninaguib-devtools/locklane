# 280 — Enlarge header logo further without increasing header height

Issue: #280

## Asked
The header logo (`.brand-logo`) was enlarged once already in #269 to 28x28, matching its
neighbours. Make it noticeably larger again, without the header (`.topbar`) growing any
taller or pushing page content down.

## Done when
- The logo image (`.brand-logo`) renders larger than its current 28x28px size.
- The `.topbar`'s rendered height is unchanged from before this change (verified visually).
- No layout regression in the topbar (brand text, actions on the right) — items stay
  vertically centered and don't wrap or overlap.

## Explicitly not
- No change to the logo asset itself (`logo.svg`) or its aspect ratio.
- No broader header/topbar redesign beyond what's needed to fit the larger logo.

## Decisions made along the way
- Grew the logo to 44x44 (from 28x28) using a negative vertical margin (`margin: -8px 0`)
  so its effective contribution to the flex row's cross-size stays at 28px — well under
  the 30px the "+ add project" button already sets as the row's height ceiling — so
  `.topbar`'s rendered height is untouched while the image paints visibly larger, centered
  in the row (Hani, 2026-08-28).
- Chose 44px over a larger size: the negative margin lets the logo overflow its own box
  into `.topbar`'s 16px padding, and 44px leaves several pixels of clearance in that
  padding on each side (measured `logoTop: 9`, `logoBottom: 53` inside a `0`–`63`
  `.topbar` box) so the image doesn't approach the border-bottom line (Hani, 2026-08-28).

## Deviations / notes
- Visual verification was done by rendering the real header markup and real stylesheets
  (`client/src/styles.css`, `app.component.css`) in headless Chrome at 1280px, before and
  after, and measuring boxes via `getBoundingClientRect()` — not by driving the
  authenticated app, which needs a live login plus a TOTP code this session does not
  have (same substitute used on #266). Measured: `.topbar` height is **63.00px in both**
  renders; `.brand-logo` goes 28.00 → 44.00px; every other header element (`.brand`
  28.5, `.button` 30, `.avatar` 28) is unmoved. A zoomed screenshot crop confirms no
  overlap with the border-bottom or the "LockLane" wordmark. A human's eyeball pass in
  the signed-in app is still worth doing at review.
