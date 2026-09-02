# 586 — Center and enlarge the selected project name in the header

Issue: #586

## Asked
The currently selected project's name is buried in the header today, fused into a
left-aligned brand link that reads "LockLane - <project name>". Make the project name
the visually prominent element in the header by centering it and giving it a modestly
larger, bolder treatment, without growing the header's height, so a user can tell at a
glance which project they're in.

## Done when
- The header's `.topbar` renders as a 3-part layout (brand/logo | project name |
  right-side controls) instead of today's 2-column `space-between`, so the project name
  sits centered relative to the bar and never overlaps the console indicator,
  add-project button, or account menu.
- The project name is its own element (decoupled from the "LockLane - " prefix in
  `headerTitle()`), rendered at a visibly larger size/weight than the current
  19px/600 (e.g. ~21px, and/or weight bumped to 700).
- The header's overall height is unchanged from today (still governed by the 44px logo
  + padding, not by the new text).
- Long project names truncate with an ellipsis (max-width) on narrow viewports rather
  than colliding with the right-side controls.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- No change to which project is selected or how selection works
  (`current-project.service.ts` internals).
- No redesign of the left-side brand/logo or the right-side controls (console
  indicator, add-project button, account menu) beyond what's needed to make room for
  the centered name.

## Decisions made along the way
- none

## Deviations / notes
- none
