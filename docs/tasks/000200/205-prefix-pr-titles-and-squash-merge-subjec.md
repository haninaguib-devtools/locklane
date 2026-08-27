# 205 — Prefix PR titles and squash-merge subjects with the issue number
Issue: #205

## Asked
PR titles opened by `/t-work` and the squash-merge commit subjects written by `/t-ship`
should start with a bracketed issue-number prefix, e.g. `[12] Remove image from page`.
After GitHub's squash-merge appends its own `(#199)` PR-number suffix, the final commit
reads `[12] Remove image from page (#199)`, giving `git log --oneline` a scannable
issue-number prefix tying every commit back to its tracker issue.

## Done when
- `/t-work` Phase 3 step 4 opens the draft PR with title `[<id>] <issue title>` instead
  of the bare issue title.
- `/t-ship`'s squash-merge subject template becomes `[<id>] <issue title> (#<pr>)`
  instead of `<issue title> (#<pr>)`.
- `CLAUDE.md`'s (i.e. `AGENTS.md`'s) "Commit messages:" convention bullet documents the
  `[<id>] <title> (#<pr>)` format.
- The updated skill text is internally consistent — no leftover reference to the old
  bare-title subject.

## Explicitly not
- Branch naming (`wip/<id>-<slug>`) is unaffected.
- No retroactive rewrite of already-merged commit history.
- `CONSTITUTION.md` §1.4's wording — confirmed during planning that it does not
  constrain the subject-line format, so it needs no edit.

## Decisions made along the way
- none

## Deviations / notes
- none
