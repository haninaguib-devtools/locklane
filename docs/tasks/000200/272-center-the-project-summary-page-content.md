# 272 — Center the project summary page content like the Overview page

Issue: #272

## Asked
When a project is selected in the sidenav with no issue open, the project summary
page (project name, status, console/delete buttons, issue-count chips, and the
repository/branch/workarea/added facts) renders top-left in the content pane,
stretching the full width. Task #251 centered the workspace Overview page's content
in a max-width column, both horizontally and vertically; the project summary page
should get the same treatment so the two landing pages read consistently.

## Done when
- With a project loaded, the project summary's content (header row, count chips,
  facts grid) sits in a column no wider than 720px, centered horizontally in the
  content pane, and centered vertically as the pane's height changes — mirroring
  `overview.component.css`'s `.overview:not(.zero)` + `.content` structure.
- The loading / error states and the delete-confirm dialog still render correctly.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.
- Whether the result visually matches the Overview page's centering is a human
  judgment call, not machine-checkable.

## Explicitly not
- No change to the workspace Overview component (already centered by #251).
- No behavioral changes to the buttons, counts, or delete flow.

## Decisions made along the way
- none

## Deviations / notes
- none
