# 325 — Sync issue-hierarchy tree with GitHub's native parent/sub-issue relationship
Issue: #325

## Asked
The dashboard's initiative/sub-issue tree is built by regexing each issue's body for a
literal `Part of: #<n>` string, a convention that predates GitHub's native sub-issue
support. `/t-open` now links children via `tracker:set-parent` (`gh issue edit
--parent`), which sets GitHub's real parent/sub-issue relationship but writes nothing
into the body — so a correctly-linked sub-issue never appears nested in the app's own
tree. The engine needs to read that native relationship and build the tree from it.

## Done when
- `CliGhClient.issues()` requests GitHub's native `parent` field via `gh issue list
  --json`, and `GhIssue` carries that data.
- `IssueTreeService` builds the initiative/sub-issue tree from that native
  relationship.
- Re-parenting #318, #319, #320 under #317 (already done via `gh issue edit --parent`)
  is reflected correctly in the dashboard tree without any body edit.

## Explicitly not
- Changing how `/t-open` writes `Split from: #<id>` — that has no GitHub-native
  equivalent (ADR-003) and stays as body text by design.

## Decisions made along the way
- Kept the `Part of: #<n>` body-text regex as a fallback, used only when
  `GhIssue.parent()` is null (haninaguib, 2026-08-29) — issues re-parented before this
  change already carry the native link and take that path; any issue never re-parented
  natively still nests correctly rather than silently becoming standalone. Reported in
  the PR per the issue's own "call it out either way."
- Added `GhIssue.parent()` (an `Integer`, null when absent) via a second,
  backward-compatible record constructor rather than changing the canonical 7-arg
  shape, so the ~35 existing `new GhIssue(...)` call sites elsewhere in the codebase
  (persistence tests, etc. — outside this task's scope) keep compiling unchanged
  (haninaguib, 2026-08-29).

## Deviations / notes
- none
