# 557 — Fix console panel clipped when release/update banner is showing
Issue: #557

## Asked
When the app shows the "a newer version is available" banner (or the in-app
update-ready banner) above the main shell, the bottom of the app — including the
project/issue console's terminal panel — gets clipped by roughly the banner's height.
The app shell (`app.component.css`) is hardcoded to `height: 100%` with
`overflow: hidden`, and the banners render as normal-flow siblings above it rather than
being sized into the layout, so the shell still claims the full host height and loses
that much off its own bottom instead of shrinking to make room. Switch the shell layout
to a flex column so the shell uses `flex: 1` and naturally shrinks when a banner above
it takes space, instead of a hardcoded `height: 100%`.

## Done when
- With the release-available banner (or the update-ready banner) visible, the
  terminal's last row/prompt in the project console and the issue-page console is fully
  visible, not clipped — verified visually by a human, since this is a
  rendering/layout defect.
- With no banner visible, the console layout is unchanged from current behavior (no
  regression to the no-banner case).
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Changing `update-banner.component.css`'s floating-pill positioning/styling — out of
  scope, this task only fixes the shell's height accounting.

## Decisions made along the way
- none

## Deviations / notes
- none
