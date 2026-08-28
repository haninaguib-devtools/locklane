# 281 — Fix centering that clips overflowing content on Overview and Project Summary pages
Issue: #281

## Asked
On the Overview page and the Project Summary page (#274), the scrollable panel centers
its content by putting `align-items: center; justify-content: center` directly on the
scrolling container. That only works while the content is shorter than the visible
window: once content is taller than the viewport, centering pushes the top of the
content above the panel's top edge, and the browser doesn't extend the scrollable range
to cover the negative offset. The heading, counts, and first several rows become
permanently unreachable — there is no scroll position that reveals them. Fix the
centering on both pages so short content still centers, but content taller than the
viewport is fully reachable by scrolling instead of being clipped at the top.

## Done when
- `.overview:not(.zero)` in `overview.component.css` and `.summary` in
  `project-summary.component.css` no longer center by putting `align-items: center`
  directly on the scrolling container.
- With enough projects/content to overflow the viewport, the full content is reachable
  by scrolling to `scrollTop: 0` (the top of `.content`'s bounding rect is at or below
  the container's top, never negative).
- With content shorter than the viewport, the page still visually centers the content
  both horizontally and vertically, matching today's appearance.
- The same fix is applied consistently to both the Overview and the Project Summary
  page.

## Explicitly not
Redesigning the Overview or Project Summary page content/layout beyond the centering
mechanism itself. Changing the empty-state (`.zero`) styling on Overview.

## Decisions made along the way
- Used the flex "auto-margin" centering trick instead of `align-items: safe center`
  (hani, 2026-08-28) — safe-alignment keyword support is inconsistent across browsers;
  switching the container to `flex-direction: column` and centering the single child
  with `margin: auto 0` centers when there's spare room and collapses to `0` (never
  negative) when content overflows, which is supported everywhere flexbox is.

## Deviations / notes
- none
