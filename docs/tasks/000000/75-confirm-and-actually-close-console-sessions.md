# 75 — Confirm and actually close console sessions on tab close
Issue: #75

## Asked
Right now clicking the x on a console tab only removes the tab from the browser's
view — the underlying PTY session on the server keeps running, so the "Consoles (xx)"
count on the console-indicator button never goes down. Add a confirmation dialog
before closing, then actually kill the session server-side, and have the count
badge reflect the drop.

## Done when
- Clicking the x on a console tab shows a confirmation dialog before anything
  happens; cancelling leaves the tab and session untouched.
- Confirming calls a new server endpoint that terminates that console's PTY session
  (not just removes a client-side record).
- After a successful close, the tab is removed from the UI and the "Consoles (xx)"
  badge count reflects one fewer console without requiring an unrelated page action
  to trigger a refresh.
- If the close request fails, the tab stays open and the user sees that it failed.

## Explicitly not
- No change to how sessions are created or reattached — ADR-002's durable/
  reattachable session model stays as-is for sessions the user does not explicitly
  close.
- No bulk "close all consoles" action.

## Decisions made along the way
- The close endpoint lives on `WorktreeController` as `DELETE
  /api/issues/{number}/worktrees/{worktreeId}` rather than on `ConsolesController`
  (which only ever served the cross-issue list) — it's the natural resource-owner
  home alongside the existing GET/POST for the same path shape, and reuses the same
  per-issue ownership visibility check as `worktrees()`. (hani, 2026-08-25)
- The confirmation dialog uses the native `window.confirm()` — there was no existing
  modal/dialog pattern anywhere in the client to reuse, and a custom one is more
  than this single yes/no prompt needs. (hani, 2026-08-25)
- The indicator's badge count updates via a small `ConsolesService.onClosed`
  RxJS Subject that `MainContentComponent` fires after a successful close, rather
  than polling or a bigger app-wide store — the indicator already owned refetching
  its own list, this just gives it a trigger beyond init/toggle. (hani, 2026-08-25)
- Closing a session removes its SQLite record (`WorktreeSessionRepository.delete`)
  in addition to killing the live `PtySession`, so it doesn't reappear in the list
  after a server restart. (hani, 2026-08-25)

## Deviations / notes
- none
