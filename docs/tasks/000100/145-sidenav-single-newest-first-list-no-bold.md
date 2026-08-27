# 145 — Sidenav: single newest-first list, no bold initiative titles
Issue: #145

## Asked
The sidenav currently shows initiatives grouped at the top of each project's section,
with their titles rendered bold; standalone tasks follow. Change the sidenav so all
top-level issues — initiatives and standalone tasks alike — appear in one list sorted
newest-created-first, and render initiative titles at the same font weight as task
titles. An initiative keeps its twisty and its nested children under it, wherever the
initiative itself lands in the list; children do not get their own top-level position.

## Done when
- `IssueTreeService.tree()` returns top-level nodes ordered by issue creation date
  descending, with initiatives interleaved among standalone tasks (no initiative-first
  grouping). A unit test asserts a newer standalone task sorts above an older
  initiative.
- Initiative rows in the sidenav render without bold: the
  `.issue-title.initiative-title` font-weight rule (or the `initiative-title` class
  binding) is removed from `sidenav.component.css`/`.html`.
- Children still nest under their initiative parent, unchanged.
- `./mvnw -B test` and the client test suite pass.

## Explicitly not
- No change to pinned-section ordering (stays in pin order).
- No change to how children are ordered within an initiative beyond inheriting the
  same newest-first sort.
- No new sort options or UI controls.

## Decisions made along the way
- none

## Deviations / notes
- none
