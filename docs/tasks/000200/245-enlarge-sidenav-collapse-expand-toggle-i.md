# 245 — Enlarge sidenav collapse/expand toggle icon
Issue: #245

## Asked
The collapse/expand toggle (the small triangle button next to each project and issue row
in the sidenav) is too small to click comfortably. Make it visibly and comfortably
bigger so it's easier to see and click, without breaking the sidenav's row layout or
alignment.

## Done when
- The toggle icon (`.twist` in `client/src/app/components/sidenav/sidenav.component.css`,
  currently 14x14px / 9px font) renders noticeably larger.
- Sidenav rows still align cleanly (icon, label, and any trailing controls stay on one
  line, no overlap or clipping) at normal and narrow sidenav widths.
- Existing sidenav client tests still pass.

## Explicitly not
- No change to the HTML structure — the CSS-only size bump is sufficient.

## Decisions made along the way
- Enlarged `.twist` / `.twist-gap` from 14x14px to 20x20px and the `.twist` font-size
  from 9px to 14px, keeping `flex: none` so the row's flex layout (icon, label, trailing
  controls) is unaffected beyond the extra 6px of width the box now occupies (hani,
  2026-08-27).

## Deviations / notes
- none
