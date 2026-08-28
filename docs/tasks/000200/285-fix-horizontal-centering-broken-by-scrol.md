# 285 — Fix horizontal centering broken by scrollbar gutter on Overview and Project Summary
Issue: #285

## Asked
On the Overview and Project Summary pages, whenever the list is tall enough to need the
vertical scrollbar, the content is visibly off-center to the left: the scrollbar eats
space from the right edge of the scrolling panel only, and centering is computed against
that scrollbar-narrowed box. When the list is short and no scrollbar appears, centering
is correct — the asymmetry only shows up once there's enough content to scroll. Verified
with a rendered CSS harness using the real stylesheets: with a long project list the
content sat 222.5px from the left edge vs 237.5px from the right (should be equal);
adding `scrollbar-gutter: stable both-edges` to the scrolling container made both cases
centered (230px/230px for both a short, non-scrolling list and a long, scrolling one).

## Done when
- `.overview` in `client/src/app/components/overview/overview.component.css` and
  `.summary` in `client/src/app/components/project-summary/project-summary.component.css`
  both set `scrollbar-gutter: stable both-edges` on the scrolling container.
- A rendered check (harness or live app) with a list long enough to trigger the
  scrollbar shows equal left/right gaps around the centered content, and a short list
  (no scrollbar) also shows equal gaps.
- The vertical scroll behavior fixed in #281 (content never clipped at the top when
  taller than the panel) is unchanged.

## Explicitly not
Redesigning the Overview or Project Summary layout beyond the centering fix. Changing
the `.zero` empty-state styling on Overview — it never scrolls, so it isn't affected.

## Decisions made along the way
- none

## Deviations / notes
- none
