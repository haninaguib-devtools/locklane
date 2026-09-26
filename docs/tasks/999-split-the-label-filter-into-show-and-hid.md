# 999 — Split the label filter into Show and Hide columns
Issue: #999

## Asked
The sidenav's label filter picker (#947) today is a single checkbox list: ticking labels shows only issues carrying any ticked label. Split the picker into two side-by-side columns, **Show** and **Hide**. The Show column keeps today's behavior exactly (empty = no filter; otherwise a node matches if it carries any Show label). The Hide column is its inverse: a node is dropped if it carries any Hide label (empty = no filter). Both are ANDed with the text, shipped, and author filters. A label can be ticked in at most one column: ticking it in one column unticks it in the other. Like the Show filter, Hide is not exempted by an open agent session. For pinned entries the same rule as today's tag filter applies: a pin itself is never removed by Show or Hide, but its children are filtered by both. The picker's single "find a label…" search box narrows both columns. The picker button's text counts both, e.g. `labels` when nothing is ticked, `2 shown`, `1 hidden`, or `2 shown, 1 hidden`.

## Done when
- The label picker renders two columns headed Show and Hide, each listing the same (search-narrowed) labels with a checkbox per label.
- `filterNode`, `filterTree`, `filterPinnedNode`, `filterPinnedTree` in `tree-filter.ts` accept a hide-label list and drop any non-pinned node (and any child, pinned or not) carrying a hidden label; an open agent session does not exempt it.
- Ticking a label in one column removes it from the other.
- The button text reflects both counts as described in the Goal.
- Unit specs in `tree-filter.spec.ts` and the sidenav component spec cover hide filtering, pinned behavior, mutual exclusion, and the button text.
- `scripts/check.sh` passes.

## Explicitly not
- Persisting the label filter selection across reloads (it is not persisted today).
- Any change to the author picker.
- Any engine/server change.

## Decisions made along the way
- The two columns are one grid: a Show checkbox, a Hide checkbox, then the label name, under
  `Show` / `Hide` headings. The label name appears once per row rather than twice, which fits
  the narrow sidebar; the checkboxes carry `aria-label="Show <label>"` / `"Hide <label>"`.
- The Hide list is a new trailing `hiddenTags` parameter on the tree-filter functions, folded
  into the existing tag check, so it inherits the tag filter's rules for agent sessions and pins.
- The picker's choices include hidden labels too, so a ticked Hide label never vanishes from
  its own picker.

## Deviations / notes
- `sidenav.component.css` was already at its 8 kB `anyComponentStyle` error budget
  (`angular.json`); any added rule failed the build. The grid's one rule is an inline
  `style` attribute on the grid, and headings and names reuse `.picker-option`. The budget
  was not raised.
- `scripts/check.sh`: client 1079/1079 pass; engine fails 16 tests (ProjectCheckoutServiceTest x3,
  ProjectWorktreesServiceTest x4, WorktreeCleanupSweeperTest x3, WorktreeCreationServiceTest x1,
  SessionRegistryReattachTest x1, ProjectAgentSessionWebSocketIntegrationTest x1,
  BellHookCommandDetachedShapeTest x2 (`setsid` missing), ProcessTreesTest x1 (`/bin/true`
  missing)) — the known local macOS environment failures; this diff touches no engine code.

## Agents
- work: claude-code / claude-opus-5-5
