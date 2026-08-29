# 318 — Issue page: replace + with a Console button tied to session state
Issue: #318 · Part of: #317

## Asked
On the issue page, replace the "+" open-console control with a button labeled
"Console." Clicking it opens (or reuses) a worktree-backed console session for that
issue through the existing worktree/session flow (`WorktreeCreationService`,
`IssuesService.startSession`). The button is hidden while a console session is open for
that issue, and reappears once that session's close completes. Closing a console must
not delete its worktree — no change from today's behavior.

## Done when
- The issue page shows a "Console" button in place of the current "+" for opening a
  worktree console.
- The button is hidden whenever a live console session exists for that issue, and
  reappears once the session finishes closing.
- Closing a console does not remove its git worktree — verified with `git worktree
  list` before and after a close.

## Explicitly not
- Changing console behavior for the "main" (non-worktree) location.
- Changing the multi-console tab strip's behavior beyond this button's visibility rule
  (the project-console page's own "+" — `[locationChoice]="false"` there already —
  keeps its current always-visible, immediate-launch behavior untouched).

## Decisions made along the way
- Implemented as three new `ConsoleTabsComponent` inputs rather than a one-off issue-page
  variant, so the existing single tab-strip component keeps serving both the issue page
  and the project-console page (haninaguib, 2026-08-29):
  - `openLabel` — the open button's text (`'Console'` from the issue page, default `'+'`
    elsewhere).
  - `directWorktree` — when `locationChoice` is `false`, which location the button
    launches directly (`true` from the issue page; the project-console page already
    passes `locationChoice="false"` and keeps its existing `false`/"main" launch).
  - `hideOpenWhenActive` — hides the open button while `tabs.length > 0` (`true` from
    the issue page only), exposed as a `showOpenButton` getter so it stays unit-testable
    the same way the rest of this component already is (no `TestBed`).
- The issue page's `app-console-tabs` binding drops the where-picker (`locationChoice
  false`) entirely — the Goal's "Console" button never offers a main-vs-worktree choice,
  so that popup is now unreachable from the issue page (it still exists, unused from
  here, behind `locationChoice`, for any future caller that wants it back) (haninaguib,
  2026-08-29).
- No engine or `IssuesService`/`WorktreeCreationService` changes: `openConsole()` in
  `MainContentComponent` already reuses an issue's existing worktree session on a
  `{worktree: true}` request (#29) and `closeConsole()` already only calls
  `DELETE .../worktrees/:id`, which the engine implements without touching the git
  worktree — both behaviors predate this task and needed no change (haninaguib,
  2026-08-29).

## Deviations / notes
- none
