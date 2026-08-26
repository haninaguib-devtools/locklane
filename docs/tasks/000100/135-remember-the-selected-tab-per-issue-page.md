# 135 — Remember the selected tab per issue page
Issue: #135

## Asked
The issue page now opens on an Overview tab. When a user switches to another tab,
navigates away (to another issue or another page), and comes back, the page resets to
Overview. The browser should remember which tab was last open for each issue, so
returning to an issue restores that issue's last selected tab. The memory is per issue
and lives client-side (e.g. in the browser's local storage) — it does not need to sync
across devices or survive clearing browser data.

## Done when
- Switching to a non-Overview tab on issue A, navigating to another route, and returning
  to issue A shows the previously selected tab, not Overview.
- The remembered tab is per issue: issue A and issue B each restore their own last tab.
- An issue never visited before still opens on Overview.
- A remembered tab that no longer exists for the issue falls back to Overview without
  errors.
- Existing client tests pass (`./mvnw -B test`).

## Explicitly not
- No server-side persistence and no cross-device sync of the selected tab.
- No change to which tabs exist or to the Overview tab's content.

## Decisions made along the way
- Added a new `ActiveTabStore` (client/src/app/services/active-tab-store.ts) rather than
  reusing the existing `ActiveConsoleStore`: that store remembers the last console a user
  interacted with for other features (the header's console picker jumping to an issue),
  and its semantics don't include "the user explicitly selected Overview," which this
  task needs to distinguish. (haninaguib, 2026-08-26)

## Deviations / notes
- none
