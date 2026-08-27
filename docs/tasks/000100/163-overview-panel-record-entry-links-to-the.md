# 163 — Overview panel: RECORD entry links to the issue instead of the record
Issue: #163

## Asked
In the overview panel's "record" row, the link label correctly shows `detail.recordPath`,
but the link target is wrong: the anchor's `[href]` is bound to `issueUrl` (built from the
GitHub issue number) instead of a URL that actually points at the task record file.
Clicking "record" currently navigates to the issue page, not the record.

## Done when
- The record row's anchor links to the record itself (a repo blob URL built from
  `repoWebUrl` and `detail.recordPath`), not to the issue.
- A new `recordUrl` getter on `OverviewTabComponent`, mirroring the existing `prUrl`
  pattern, builds that link.
- `overview-tab.component.spec.ts` asserts the record link's href is derived from
  `recordPath` and is distinct from `issueUrl`.

## Explicitly not
No change to `issueUrl` or `prUrl` themselves, and no new `@Input` for the branch/ref —
`recordUrl` falls back to `main` when `detail.branch` is unknown (record found on disk
before a PR/branch is reported), same as any other unpushed-branch edge case elsewhere in
this panel.

## Decisions made along the way
- `recordUrl` uses `detail.branch` as the blob ref when present (the record is guaranteed
  to exist on that branch by the time it's on disk) and falls back to `main` otherwise —
  `detail.branch` comes from the PR's `headRefName` server-side
  (`IssueDetailService.detail`), so it can be null while `recordPath` is already populated
  (record written to disk before the PR is opened). (hani, 2026-08-27)

## Deviations / notes
- none
