# 819 — Sidebar no longer scrolls and usage widget is not pinned to the bottom after #815
Issue: #819

## Asked
Since the desktop-style layout change (#815, merged as #816), the left sidebar is broken
in two ways a user sees immediately: when the case list is taller than the window it no
longer scrolls, and the usage widget no longer sits pinned at the bottom of the sidebar —
it follows the list down and gets clipped with it. Restore both behaviours: the case list
scrolls inside the sidebar, and the usage widget stays at the sidebar's bottom edge
regardless of list length.

Cause: #815 gave the `app-sidenav` host element `display: flex; flex: 1; min-width: 0;
min-height: 0` (row direction, no height of its own) and replaced `.sidenav { height:
100% }` with `flex: 1`. The host sits inside `<aside class="sidebar">` in
`app.component.html`, which is a plain block (`flex: none; overflow: hidden`), not a flex
container — so `flex: 1` on the host does nothing and its height became content-driven.
The `.sidenav` panel grows to its content, `.sidenav-scroll` never gets a bounded height
to scroll within, and the aside clips the overflow, widget included. Before #815 the host
was inline, so `.sidenav { height: 100% }` resolved against the aside and filled it.

## Done when
- With more cases than fit in the window, the sidebar's case list scrolls vertically
  inside the sidebar (the `.sidenav-scroll` element scrolls; the page and the aside do
  not) — a human checks this in a browser at a short window height.
- The usage widget is visible at the bottom of the sidebar regardless of case-list
  length, both when the list is short and when it overflows — human check, same session.
- `grep -n 'height: 100%' client/src/app/components/sidenav/sidenav.component.css`
  matches the `:host` rule (or an equivalent bounded-height rule on the host).
- `./mvnw -B test` passes (build inputs change, so it runs).
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No change to the sidebar's visual restyle from #815 (spacing, colours, button sizes,
  selection styling) — only the host/panel height model.
- No change to `app.component.css`/`.sidebar`, the resizer, the usage widget component,
  or the window-chrome directive.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Keep the host a flex container but make it a column with `height: 100%`, rather than
  reverting to the pre-#815 inline host: a column flex host with a bounded height is
  the layout #815 evidently intended, and it keeps `.sidenav`'s `flex: 1; min-height: 0`
  meaningful instead of dead (agent, 2026-09-08).

## Deviations / notes
- none
