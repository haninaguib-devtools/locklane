# 959 — Sidenav author/label pickers grow to fill row instead of truncating
Issue: #959

## Asked
On the sidenav's second filter row, the author and label picker buttons
(`.picker-button` in `client/src/app/components/sidenav/sidenav.component.html`/`.css`)
truncate their selected-value label instead of growing. `.controls-row.pickers` lays
the two `.picker-wrap` buttons out with `white-space: nowrap` and no flex-grow, so once
several authors or labels are selected the button text is clipped inside the sidenav's
fixed width. The two pickers should grow to the right, sharing the row's remaining
width, so selected values stay legible instead of being cut off.

## Done when
- With several authors and/or labels selected, the picker button text is not visually
  truncated (no `text-overflow: ellipsis`/clipping) as long as it fits within the
  sidenav's width; the row still doesn't overflow the sidenav horizontally.
- The two picker buttons expand to fill the available width of the second filter row
  (`.controls-row.pickers`) rather than staying at their intrinsic/fixed width.
- Existing filter-row behavior (text filter, Open toggle, dropdown open/close) is
  unaffected.

## Explicitly not
none

## Decisions made along the way
- Gave `.picker-wrap` `flex: 1` so the two picker wraps split the row's remaining
  width evenly, and `.picker-button` `width: 100%; overflow: hidden;` so the button
  fills its wrap and clips (rather than overflows) when a selection is wider than the
  space available. No `text-overflow: ellipsis` was added — "Done when" only requires
  no clipping while the text fits, and the client's Angular per-component CSS budget
  (8 KB) left no headroom for an ellipsis span (see Deviations).

## Deviations / notes
- First pass wrapped each button's label text in its own `<span>` to add
  `text-overflow: ellipsis`. That pushed
  `sidenav.component.css` over Angular's 8 KB per-component style budget
  (`ng build` failed). Reverted the HTML change and kept the fix CSS-only
  (`overflow: hidden` hard-clips instead of ellipsis-truncating when overcrowded),
  which fits under budget and still satisfies the issue's "Done when".

## Agents
- work: claude-code / claude-sonnet-5
