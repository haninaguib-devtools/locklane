# 477 — Add /l-release: one command from release version to dispatched release and snapshot bump
Issue: #477

## Asked
A maintainer cuts a release with one command. `/l-release <version>` gates on the
`<revision>`/tag preconditions, then composes existing pipeline stages — `/t-open` to
create a fully shaped release-notes task, `/t-drive <id>` (solo mode) to carry it to
`/t-ship`'s merge gate, whose confirmation is worded to also authorize the post-merge
`release.yml` dispatch — then opens and drives a follow-up snapshot-bump task that stops
at its own ordinary merge gate. A new ADR (docs/adr/106) records `/l-release` as a
second explicitly-invoked exception to ADR-001 D1, bounded as a parameterized
composition of `/t-open` + `/t-drive`.

## Done when
- `.claude/skills/l-release/SKILL.md` exists and `docs/adr/106-*.md` exists;
  `./.t-workflow/scripts/consistency-check.sh` exits 0.
- The SKILL.md procedure: gates on `<revision>` mismatch and existing tag (stop, no
  writes); invokes `/t-open` and `/t-drive` by their contracts; reaches exactly two
  human stops (notes-task merge gate worded to also authorize the dispatch; bump-task
  merge gate); dispatches and verifies the release only after the first confirmation;
  defaults the bump to patch with an explicit override argument.
- `grep -q "ADR-001" docs/adr/106-*.md` finds the exception statement, and the ADR names
  its bounds (composition only — no new stage semantics, no auto-confirmation).
- A human judges a cold read of SKILL.md unambiguous: a fresh session could run
  `/l-release 0.1.1` from it alone.

## Explicitly not
- No changes to `release.yml`, `generate-release-notes.sh`, or the existing `t-*`
  skills — composition only; a defect found in them is its own issue.
- No automation of the version-choice judgment: the patch default is a suggestion the
  human overrides or confirms at a gate, never a silent decision.
- No auto-confirmation anywhere: both merge gates remain explicit human stops; a
  dispatch never happens before the first gate's confirm.

## Decisions made along the way
- none yet

## Deviations / notes
- none yet
