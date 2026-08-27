# 110 — Filter issues by has-active-branch
Issue: #110 · Part of: #109

## Asked
Add a sidebar filter that shows only issues that currently have an active branch/PR
(i.e. actively being worked on), alongside the existing text and "hide shipped"
filters.

## Done when
- `TreeNode` (`client/src/app/models/issue.model.ts`) carries a branch/active-work
  flag, populated from the existing branch data already available elsewhere in the
  app (see the separate model with a `branch: string | null` field).
- `tree-filter.ts` supports a new "has active branch" filter criterion, ANDed with
  the existing text/hide-shipped filters the same way they combine today.
- The sidebar UI (`sidenav.component.html`/`.ts`) exposes a toggle for this filter
  next to the existing filter controls.
- Manual check: start work on an issue (branch exists), confirm it survives the
  "active branch" filter; an issue with no branch is filtered out.

## Explicitly not
No change to how branches are created or tracked — only surfacing existing branch
data as a filter.

## Decisions made along the way
- The issue's Scope line names only `client/src/app/components/sidenav/` and
  `client/src/app/models/issue.model.ts`, but the branch data it points to
  (`IssueDetail.branch`) is only computed per-issue on demand, not on the bulk
  `/tree` endpoint the sidebar reads. Satisfying the Done-when requires
  `TreeNode`/`IssueTreeService` (`engine/src/main/java/dev/locklane/engine/github/`)
  to carry the same signal, reusing the already-cached
  `GhIssueCache.pullRequestForIssue` lookup that already powers
  `IssueDetail.branch` — no new network calls. The human confirmed expanding scope
  to those two engine files rather than stopping for a `/t-plan` re-scope (asked
  mid-task, 2026-08-27).
- Adding the new required `hasActiveBranch` field to the `TreeNode` interface breaks
  compilation of two other spec files outside the stated scope
  (`client/src/app/services/issues.service.spec.ts`,
  `client/src/app/components/project-summary/project-summary.component.spec.ts`)
  that construct `TreeNode` fixtures. Updated their fixtures mechanically (added the
  field) as an unavoidable consequence of the scope decision above, not a separate
  feature change.

## Deviations / notes
- none beyond the scope expansion recorded above.
