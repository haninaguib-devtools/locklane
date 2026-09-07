# 767 — Widen project page's main column to match the issue page
Issue: #767

## Asked
On a project's summary page the main content sits in a fixed-width (720px), centered
strip, while the equivalent column on an issue's Overview tab — beside the same
220px past-sessions rail — fills all the width available. Since the past-sessions
rail arrived on the project page in v0.2.19 (#754), the page reads as if the rail
eats too much of it: the content is squeezed into a narrow centered strip with large
empty gutters on both sides. The project page's main column should size the way the
issue page's does — filling the space next to the rail — so the page uses its full
width.

## Done when
- `client/src/app/components/project-summary/project-summary.component.css` no longer
  caps `.content`'s width or centers `.main-col`'s children — the main column fills
  the width available next to `.sessions-rail`, the same way
  `client/src/app/components/overview-tab/overview-tab.component.css`'s `.main-col`
  already does.
- Visually confirmed in the running app: the project summary page's content area
  extends to fill the space next to the past-sessions rail, with no large empty
  gutters on either side, matching the issue Overview tab's layout at a wide
  viewport. (human-judged)
- Existing tests for `ProjectSummaryComponent` still pass.

## Explicitly not
- Changing the past-sessions rail's own width or content — it is already 220px,
  fixed, matching the issue page's rail; only the main column's width behavior
  changes here.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Removed the `.content` wrapper `<div>` from the template rather than leaving a
  class with no rule behind it. The issue's Scope allows the `.component.html` edit
  "only if removing the now-unnecessary `.content` wrapper requires it", and it does:
  the wrapper existed solely to carry the 720px cap. The children keep their previous
  markup and order; only the one enclosing `<div>` and its closing tag go. (Claude,
  2026-09-07)
- Dropped the `.main-col > * { margin: auto 0 }` rule that #281 introduced along with
  its centering. #281's concern was that a centered column taller than the panel got
  its top clipped off-screen; with no centering at all, content flows from the top and
  is reachable at scrollTop 0 by construction, so the guarantee is preserved by
  removing the mechanism rather than keeping it. The `.main-col` padding
  (`20px 24px`) and `scrollbar-gutter` stay as they were — the issue changes width
  behavior only. (Claude, 2026-09-07)

## Deviations / notes
- The "visually confirmed in the running app" done-when is human-judged and stays
  the human's. As supporting evidence, the layout was rendered in a static HTML
  harness (the real `styles.css` variables plus this component's stylesheet and
  reproduced markup, headless Chrome at 1600px wide) before and after the change —
  the same approach #281 used. Before: a 720px centered strip with wide empty gutters
  on both sides of the content. After: the content spans the full width up to the
  220px past-sessions rail, top-aligned, matching the issue Overview tab. Not a
  substitute for the human's look at the running app. (Claude, 2026-09-07)
