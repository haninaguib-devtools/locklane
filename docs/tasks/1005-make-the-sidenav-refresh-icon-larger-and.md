# 1005 — Make the sidenav refresh icon larger and spin on its own centre
Issue: #1005

## Asked
The sidenav's refresh button, beside the "filter cases…" input, shows its icon as the
text glyph `⟳` at `font-size: 13px` (`client/src/app/components/sidenav/sidenav.component.html`,
`.refresh` in `sidenav.component.css`). At that size the bent arrow is hard to tell is an
arrow at all. While a refresh runs, `.refresh.spinning` puts the `refresh-spin` animation on
the whole 28×28 button, and the glyph's font metrics leave it off-centre inside that box,
so it visibly wobbles around a point that is not its own centre. Replace the text glyph
with an icon that reads clearly as a circular refresh arrow at a larger size (an inline
SVG, roughly 16px, drawn in `currentColor` so the existing muted/hover colours still apply),
centred in the button, and make the spin rotate only that icon, around its own centre.
The button keeps its 28×28 hit area, its `aria-label`/`title`, its disabled-while-refreshing
behaviour and its place in the filter row.

## Done when
- `grep -n '⟳' client/src/app/components/sidenav/sidenav.component.html` finds nothing; the
  button renders an icon visibly larger than today's 13px glyph and recognisable as a
  refresh arrow.
- While `refreshing` is true, the icon (not the whole button) spins, and it rotates
  around its own centre — no lateral wobble (`transform-origin` at the icon's centre,
  icon centred with flex in the button).
- The sidenav spec still passes, with an assertion that the spinning class/animation is
  applied to the icon element while refreshing.
- The button's colours, hover background, disabled opacity and 28×28 size are unchanged.

## Explicitly not
- No change to what refresh does, its timing, or the error banner.
- No icon library added; no other button's icon changes.

## Decisions made along the way
- The icon is a 16×16 inline SVG (`.refresh-icon`): a 315° stroked arc of radius 5
  centred on (8,8) plus a filled arrowhead at its open end, both in `currentColor`, so
  the button's muted/hover colours apply unchanged. The viewBox centre is the arc's
  centre, so `transform-origin: center` spins it on its own axis.
- `.spinning` moved from the button to the SVG. Width/height, colours, hover
  background, disabled opacity and the mobile 32px override are untouched; the
  `font-size: 13px` that only sized the glyph is gone.
- The existing `refresh() re-fetches everything` spec now asserts the icon carries
  `spinning` (and the button does not) while refreshing, and loses it once settled.

## Deviations / notes
- Centring uses `line-height: 0` on the button (no line box around the SVG, so the
  button's native centring puts it dead centre), not flex as the Done-when's
  parenthetical suggests. Reason: `sidenav.component.css` is already at its 8 kB
  `anyComponentStyle` error budget on main (7.99–8.00 kB built); the flex trio put the
  build 131 bytes over, grid 10 over. The selector is the bare `.spinning` and the
  keyframes keep only `to` for the same reason. Budget left as is (angular.json is out
  of scope and raising it would loosen a check). Verified in headless Chrome: icon
  centre sits on the button centre, rotated or not.

## Agents
- work: claude-code / claude-opus-5-5
