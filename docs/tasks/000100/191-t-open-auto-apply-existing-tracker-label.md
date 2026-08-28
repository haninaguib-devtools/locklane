# 191 — t-open: auto-apply existing tracker labels beyond the fixed classification set
Issue: #191

## Asked
`/t-open` currently labels every task issue with exactly one classification label, from
the fixed list hardcoded in `docs/adapters/TRACKER.md` (`bug`, `enhancement`,
`documentation`, `question`). That list can't reflect a project's own vocabulary (e.g.
component or kind labels a human already created in the tracker). Teach `/t-open` to
discover whatever labels already exist in the tracker at issue-creation time (a new
`tracker:list-labels` operation) and, in addition to the required classification label,
apply any of those existing labels that clearly fit the issue — never inventing a label
name itself, and defaulting to skip (not guess) when a discovered label's fit is
unclear.

## Done when
- `docs/adapters/TRACKER.md` documents `tracker:list-labels` (contract + a command per
  backend), in the same table format as the file's other operations.
- `docs/adapters/TRACKER.md` names the workflow-reserved labels excluded from auto-apply
  consideration: the four classification labels plus `initiative` and `cancelled`.
- `.claude/skills/t-open/SKILL.md`'s labeling step calls `tracker:list-labels`, filters
  out the reserved set, and applies zero or more of the remainder whose name or
  description unambiguously fits the issue — skipping any that don't.
- The existing required-classification-label behavior (closest fit, never left
  unlabeled) is explicitly preserved and the two passes' opposite defaults are clear.
- Human check: reading the updated SKILL.md step against two worked examples (a `spike`
  label with a clear description vs. one with none) makes plain which pass each falls
  into — no live tracker in CI to exercise this against.

## Explicitly not
- Changing or growing the fixed classification label set itself.
- Auto-*creating* project-specific labels (an `tracker:ensure-labels` equivalent for
  this new set) — discovery only ever reads and applies labels a human already created.

## Decisions made along the way
- none

## Deviations / notes
- none
