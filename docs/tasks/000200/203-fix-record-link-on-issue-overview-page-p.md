# 203 — Fix record link on issue overview page pointing to deleted branch
Issue: #203

## Asked
The record link on the issue overview page always points at the task's `wip/<id>-<slug>`
git branch. Once `/t-ship` merges the PR, that branch is deleted, so the link 404s even
though the record file still exists on `main`. The link should keep working after an
issue ships: once the issue is closed/shipped, build the record URL against `main`
instead of the (now-deleted) branch.

## Done when
- `overview-tab.component.spec.ts` has a test asserting that for a closed/shipped issue,
  `recordUrl` uses `main` (not the branch) even when a `branch` value is present on the
  detail.
- Existing tests (open issue → branch-based link; no branch → `main` fallback; no record
  path → null) still pass.
- The shipped/closed signal used is consistent with how the sidenav already determines
  shipped state (`TreeNode.state === 'CLOSED'`).
- `./mvnw -B test` passes.

## Explicitly not
Pinning the record link to the PR's merge commit SHA instead of `main` — nothing in the
codebase currently stores a merge commit SHA, and `main`-relative is sufficient to stop
the 404.

## Decisions made along the way
- No backend change was needed. `OverviewTabComponent` already receives the full
  `GhIssue` as its `issue` input (`main-content.component.html` passes `[issue]="issue"`,
  fetched live per-issue by `IssuesService`), and `GhIssue.state` already mirrors
  GitHub's issue state the same way `TreeNode.state` does — both come straight from the
  cached `gh issue list --json ...,state,...` payload. So `recordUrl` reads
  `this.issue.state === 'CLOSED'` directly instead of the scope's fallback option of
  plumbing a new field through `IssueDetail`/`IssueDetailService`/`IssueController`,
  which would have duplicated a signal the component already had (hani, 2026-08-27).

## Deviations / notes
- none
