# 947 — Multi-select author and label pickers on a second sidebar filter row
Issue: #947

## Asked
The sidebar's issue tree currently has a single-choice author dropdown (#930) beside the text filter, and no UI at all for the label filter that `tree-filter.ts` already supports through its `tags` argument (#111; the sidenav passes `[]`). Replace the dropdown with a multi-select author picker and add a multi-select label picker, both on a new second row of the sidebar controls, under the existing text filter / refresh / Open row, so the first row stops getting crowded. Each picker is a button that opens a checkbox list; the button reads `authors` / `labels` when nothing is ticked and a count (`2 authors`, `2 labels`) otherwise. Within one picker, ticked entries OR together; empty means no filter; each picker ANDs with the text filter, the Open toggle and the other picker, and follows the tag filter's existing pin rule (never removes a pinned node itself). The author picker lists every distinct GitHub login across the loaded trees, plus any ticked login that has since left the tree, sorted. The label picker lists every distinct label across the loaded trees the same way, and carries a search text box at the top that only narrows the picker's own list to labels containing the typed text (case-insensitive); it never filters issues, and a ticked label stays ticked while the search hides it. Neither picker is stored in workspaces (#934); like the existing author filter, the selection is plain component state. Agreed mockup: https://claude.ai/artifact/8irXp6CvK68dFNZYe1kRmV

## Done when
- The sidebar controls show two rows: row 1 is the text filter, refresh button and Open toggle unchanged; row 2 holds an `authors` button and a `labels` button.
- Clicking either button opens a checkbox list; ticking several entries keeps issues matching any of them; unticking all restores the unfiltered tree; the button label shows the ticked count.
- The label picker has a search box at its top that narrows only the list of labels shown; typing text that hides a ticked label leaves that label ticked and its filter in effect.
- `filterTree` / `filterPinnedTree` accept a list of authors (any-of) in place of the single author string, and the sidenav passes its ticked labels as the existing `tags` argument; `tree-filter.spec.ts` and `sidenav.component.spec.ts` cover both pickers, and `cd client && npm test` passes.
- The project's configured check passes.

## Explicitly not
- Saving the author or label selection in workspaces (#934) or anywhere persistent.
- Changing the engine or the issue payload; labels and author already arrive on `TreeNode`.
- Any change to the text filter, refresh button or Open toggle.

## Decisions made along the way
- `filterTree` / `filterPinnedTree` keep their parameter order; the last argument changes type from one login (`string`) to a list (`string[]`), any-of, so it reads like the existing `tags` argument.
- The label picker's search box is plain component state (`labelSearch`) that narrows the `labels` getter into `visibleLabels`; the tree only ever sees `filterLabels`.
- Both pickers close on the same `document:click` listener the row menus use; clicks inside a picker stop propagation so ticking several boxes keeps it open.
- CHANGELOG gets an Unreleased "Features" line, as #928 did.

## Deviations / notes
- The first `scripts/check.sh` run failed: `sidenav.component.css` went 895 bytes over the client's 8 kB per-component style budget. Fixed by sharing rules (the Open toggle's checkbox, the text filter's field look, `.menu`'s popover chrome) instead of raising the budget; the pre-existing 4 kB warning on that file is unchanged.
- The second `scripts/check.sh` run built the client and passed its tests, then failed in the engine on the 14 pre-existing environment-only tests on this machine (SessionRegistryReattachTest, BellHookCommandDetachedShapeTest, ProjectCheckoutServiceTest, ProjectWorktreesServiceTest, WorktreeCreationServiceTest, WorktreeCleanupSweeperTest); the diff touches no engine file and CI decides.

## Agents
- work: claude-code / claude-fable-5-1
