# 785 — Project page does not fill the window beside the past-conversations rail
Issue: #785

## Asked
On a project's summary page (shown when a project is selected but no issue is), the
page as a whole stops well short of the window's right edge at a wide viewport: the
main column and the 220px past-conversations rail together occupy roughly the left
half of the space available, and the rest is empty `--window` background. The rail
should be pinned to the right edge with the main column growing to fill everything
left of it, the way an issue's Overview tab already lays out.

The cause is one level above the component's own layout rules: `.project-pages` in
`client/src/app/app.component.css` is a flex row, and `app-main-content` and
`app-project-console` each declare a `:host { display: flex; flex: 1; min-width: 0;
height: 100%; }` rule so they grow to fill it, while `app-project-summary` declares no
`:host` rule at all. As a flex item with the default `flex: 0 1 auto`, its width is its
content's max-content width, so every "fill the available width" rule inside the
component — including the ones #767 added — stretches inside a box that is itself only
as wide as its own text. #767 removed a 720px cap on an inner wrapper that was never
the binding constraint, which is why it changed nothing visible.

## Done when
- `client/src/app/components/project-summary/project-summary.component.css` declares a
  `:host` rule that makes the component fill `.project-pages` the same way
  `client/src/app/components/main-content/main-content.component.css`'s `:host` does
  (`display: flex; flex-direction: column; flex: 1; min-width: 0; height: 100%;` or
  equivalent), so `.summary` inside it receives the full width available.
- Visually confirmed in the running app, not in a harness that renders the component's
  inner section alone: at a wide viewport, the project summary page's
  past-conversations rail sits against the window's right edge, and the main column
  fills everything between the sidenav and the rail, matching the issue Overview tab's
  layout. A harness that mounts `<section class="summary">` outside an
  `app-project-summary` host inside a flex-row parent cannot reproduce the defect and
  is not evidence. (human-judged)
- `grep -n ':host' client/src/app/components/project-summary/project-summary.component.css`
  matches.
- Existing `ProjectSummaryComponent` specs still pass.

## Explicitly not
- Changing the rail's width or content, or the main column's inner rules from #767 —
  those are correct once the host stretches.
- Touching `client/src/app/app.component.css` or the other page components' host rules.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Copied `main-content.component.css`'s `:host` rule verbatim (`display: flex;
  flex-direction: column; flex: 1; min-width: 0; height: 100%;`) rather than a
  variant: the issue names it as the reference, `project-console` uses the identical
  rule, and the existing `.summary { flex: 1; min-height: 0; }` already expects a
  column-direction flex parent. (Claude, 2026-09-07)

## Deviations / notes
- The "visually confirmed in the running app" done-when is human-judged and stays the
  human's — the issue itself rules out a component-only harness as evidence, so none
  was built for this task. The confirming look is expected at `/t-ship`'s gate or
  after the release reaches a laptop. (Claude, 2026-09-07)
