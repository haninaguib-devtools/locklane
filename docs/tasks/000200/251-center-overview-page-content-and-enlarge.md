# 251 — Center Overview page content and enlarge its empty state
Issue: #251

## Asked
Center the Overview page's content in the content area, both horizontally and
vertically, and make the "no projects yet" empty state more prominent by scaling up its
typography and spacing. Today, when a workspace has projects, the overview's heading,
stat chips, and project list flow top-left within the content pane with no centering
wrapper; when a workspace has no projects yet, the empty state is already centered but
reads as a quiet placeholder rather than the page's main moment.

## Done when
- With projects present, the Overview content (heading, stat chips, project list) is
  visually centered within the content pane both horizontally and vertically — e.g. a
  flex column with `align-items`/`justify-content: center` wrapping that content,
  matching the centering idiom the app already uses for its empty state and dialogs
  (`overview.component.css`'s `.zero-state`, `confirm-dialog.component.css`'s
  `.backdrop`).
- The centered content stays centered as the pane's available height changes rather than
  staying pinned to the top.
- The existing "no projects yet" empty state keeps its current centering but is scaled up
  (larger heading, roomier body copy, a larger call-to-action button) so it reads as the
  page's primary moment rather than a quiet placeholder.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.
- Whether the result visually matches the intended design is a human judgment call, not
  machine-checkable.

## Explicitly not
- none

## Decisions made along the way
- Wrapped the heading/stat-chips/project-list markup in a new `.content` element and gave
  `.overview:not(.zero)` a flex column centering rule, mirroring the existing
  `.zero-state` idiom rather than inventing a new one. — Hani, 2026-08-27

## Deviations / notes
- none
