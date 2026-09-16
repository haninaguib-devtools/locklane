# 969 — Show Open-in-new-window on every workspace row, left-aligned
Issue: #969

## Asked
The header's workspace picker dropdown (`WorkspacePickerComponent`) lists "All projects", one row per saved workspace, and "New workspace…". Today the per-row actions (Edit projects, Rename, Open in new window `↗`, Delete) render only on the active workspace's row, so a user can only pop out the workspace they are already viewing. Make the `↗` "Open in new window" action available on every workspace row, and render it as the left-most action in the row's action group so the icon sits in the same column on every row. Edit projects, Rename and Delete keep rendering only on the active row. `openInNewWindow` already takes the workspace as an argument and puts its id in the `ws` query param, so the behaviour needs no change — only where and in what order the button renders.

## Done when
- With the picker open, every `workspace` row shows the `↗` button (aria-label "Open in new window"); clicking it on a non-active workspace opens a new window with that workspace's `ws` id, as it does today for the active one.
- On the active row, `↗` is the first button in `.actions`, before Edit projects / Rename / Delete; on non-active rows it is the only action and sits in the same column.
- The "All projects" and "New workspace…" rows are unchanged.
- `client/src/app/components/workspace-picker/workspace-picker.component.spec.ts` covers a non-active row having `↗` and not Edit/Rename/Delete, and the button order on the active row.
- `npm test` in `client/` passes; the configured `check` command passes.

## Explicitly not
- Showing Edit projects, Rename or Delete on non-active rows.
- Changing what "Open in new window" does or how the workspace is carried in the URL.
- Any change to the sidenav or agent-session-indicator widgets.

## Decisions made along the way
- The `.actions` span now renders on every workspace row with `↗` first; only the three
  active-row buttons stay inside the `@if (isActive(row))`. No CSS change was needed:
  `.actions` already lays its buttons out in a flex row, so a single `↗` sits in the
  same column as the active row's first button.

## Deviations / notes
- `scripts/check.sh` ran `./mvnw -B test`: client module PASS (all specs incl. the new
  one); engine module FAIL on this Mac only for environment reasons unrelated to this
  client-only diff (`setsid` absent on macOS in BellHookCommandDetachedShapeTest,
  `/bin/true` absent, worktree/credential-helper tests, and one 5 s await timeout under
  full load). Reported as FAIL; CI is the verdict.

## Agents
- work: claude-code / claude-fable-5-1
