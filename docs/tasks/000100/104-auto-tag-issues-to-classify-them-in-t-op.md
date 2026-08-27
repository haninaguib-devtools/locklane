# 104 — Auto-tag issues to classify them in t-open
Issue: #104

## Asked
When `/t-open` creates an issue, locklane should automatically add a label that
classifies what kind of issue it is (bug, feature, docs, etc.) — for future
grouping/filtering, not needed right away, just captured so it isn't forgotten. The
Overview panel (where an issue's record, checks, and body are shown) should also
display an issue's tags, so a human can see the classification without leaving the app.

## Done when
- `/t-open` (or a step it calls) assigns one classification label to every task issue it
  creates, without a human picking it by hand. (Tracking/`initiative` issues are exempt —
  see Decisions.)
- The tag set is documented in `docs/adapters/TRACKER.md`.
- The Overview panel (`client/src/app/components/overview-tab/`) displays an issue's
  tags/labels.
- Existing `/t-open` behavior (issue creation, body template, the three machine-read
  markers) is unaffected.

## Explicitly not
- Building any UI or automation to group/filter issues by these tags — that's #111
  (`Blocked-by: #104`).
- Retroactively tagging existing issues.

## Decisions made along the way
- Classification set reuses GitHub's existing default labels already present in this
  repo — `bug`, `enhancement`, `documentation`, `question` — instead of inventing a new
  taxonomy. (haninaguib, 2026-08-27, per plan on issue #104)
- The classification label applies to task issues only, not to `initiative`-labeled
  tracking issues: an initiative is a coordination shape, not a bug/feature/docs kind,
  and already carries the `initiative` label. (haninaguib, 2026-08-27, per plan on issue
  #104)
- The 4 labels are added to `scripts/github-bootstrap.sh`'s idempotent `label()` calls
  rather than relying on GitHub's implicit repo-creation defaults, so they're guaranteed
  to exist and the mechanism stays honest about being backend-agnostic in principle.
  (haninaguib, 2026-08-27, per plan on issue #104)

## Deviations / notes
- none
