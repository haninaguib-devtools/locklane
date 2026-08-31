# 456 — Update console widget on tab rename and drop agent suffix from project console labels
Issue: #456

## Asked

Two related fixes to how project-level console tabs are titled and how those titles
reach the header consoles widget:

1. **The widget goes stale on rename.** Renaming a project console's tab updates the
   tab strip immediately, but the header consoles widget keeps showing the old name in
   its `Project - <tab text>` rows until reload — its `entries` stream only refetches
   on `ConsolesService.onOpened`/`onClosed`, and `renameConsole` emits nothing it
   listens to. Add a rename notification (`notifyRenamed()`/`onRenamed` on
   `ConsolesService`, merged into the widget's refetch trigger), fired after the
   optimistic update and again after the error-path revert. Correct the comment above
   `toProjectEntries` claiming renames already show immediately.
2. **Drop the agent suffix from project console tab labels.** `labelProjectConsoles`
   appends ` · <agent>` to auto labels; remove it so project-level auto labels are just
   "console", "console 2", … The widget's rows change with it automatically (#449).
   Issue-console labels (`labelConsoles`) are untouched.

## Done when

- With two project consoles open, renaming one tab updates its `Project - <name>` row
  in the header consoles widget without a page reload; a failed rename reverts the row
  too. Covered by a component/unit spec; final behavior confirmed by a human in the
  running app.
- `labelProjectConsoles` unit tests assert auto labels "console", "console 2" with no
  agent suffix, and `grep -rn "console · " client/src/app --include='*.ts'` finds no
  remaining expectation of the suffix in non-spec code.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not

- Propagating a rename made in *another* browser/session: `remoteOrReconnected$` only
  covers open/close events, and remote renames stay invisible until the next
  open/close or reload. Boundary only for this task.
- No change to issue-console tab labels (`labelConsoles`) — "main · claude" keeps its
  agent suffix.
- No engine/API changes; the rename endpoint and `displayName` persistence stay as
  they are.

## Decisions made along the way

- `onRenamed` is a local-only stream (no `remoteOrReconnected$` merge, unlike
  `onOpened`/`onClosed`): remote renames are the issue's own declared non-goal, and the
  widget already refetches on the remote-change stream via `onOpened`/`onClosed`, so
  folding it in here would only duplicate triggers. (agent, 2026-08-31)

## Deviations / notes

- none
