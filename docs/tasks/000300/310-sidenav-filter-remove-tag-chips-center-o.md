# 310 — Sidenav filter: remove tag chips, center Open toggle
Issue: #310

## Asked
In the sidenav's filter controls, remove the row of classification tag-filter chips
entirely (the buttons rendered under `.tag-chips` in
`client/src/app/components/sidenav/sidenav.component.html`, backed by
`classificationTags`/`selectedTags`/`isTagSelected`/`toggleTag` in
`sidenav.component.ts`), and center the "Open" checkbox filter horizontally within the
sidenav's filter section.

## Done when
- The tag-chip row and all now-dead tag-filtering code (state, template, and any CSS
  scoped only to `.tag-chips`/`.tag-chip`) are removed from the sidenav component.
- Filtering by tag is no longer part of the sidenav's behavior; the `filterText` search
  box and the "Open" (`hideShipped`) checkbox remain the only filter controls.
- The "Open" checkbox/label is horizontally centered within the sidenav's filter
  section (visually verified).
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
- `tree-filter.ts`'s `tags` parameter and tag-matching logic — the issue scopes the
  removal to `sidenav.component.ts`'s own state/template/CSS, and that shared utility
  stays a general-purpose filter with its own tests exercising the parameter directly.
  It becomes unreachable from the sidenav once `selectedTags` is gone, but removing its
  API is out of scope here.

## Decisions made along the way
- none

## Deviations / notes
- none
