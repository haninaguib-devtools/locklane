# 186 — Sidenav: show each project's open-issue count next to its name
Issue: #186

## Asked
In the sidenav, each project's header row shows only the project name. Add the number
of open issues in parentheses after the name — e.g. `locklane (9)` — so a glance at the
sidenav tells how much open work each project has, even when its section is collapsed.

## Done when
- Each project header row in the sidenav renders the project name followed by the count
  of open issues in that project, formatted as `name (N)` — e.g. `locklane (9)`.
- The count includes every issue in the project's tree whose state is open, counting
  initiative children as well as top-level rows, and is unaffected by the sidenav's
  text filter and "opened issues" toggle.
- The count updates when the tree refreshes (manual refresh, `issuesChanged` events,
  reconnect) without a page reload.
- While a project is still cloning or failed (no tree yet), no count is shown.
- `./mvnw -B test` passes, including a sidenav component spec asserting the rendered
  count.

## Explicitly not
- No engine/API change — the count is computed from the already-fetched tree.
- The pinned section's project name lines are unchanged; the count appears on the main
  project section headers only.

## Decisions made along the way
- Open means `state === 'OPEN'` on the tree node, matching the issue's wording; the
  tree endpoint only ever emits `OPEN`/`CLOSED`, so this agrees with `tree-filter.ts`'s
  `state !== 'CLOSED'` convention (agent, 2026-08-27).
- The count is rendered inside the existing `.project-label` span so it sits directly
  after the name (`name (N)`); the label is `flex: 1`, so a sibling span would be
  pushed to the far edge of the header instead (agent, 2026-08-27).

## Deviations / notes
- none
