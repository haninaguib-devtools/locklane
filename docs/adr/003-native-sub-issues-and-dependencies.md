# ADR-003: Native sub-issues and dependencies replace body markers

**Status:** Accepted · 2026-08-28
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001 if a
second maintainer joins — workflow §13 Q9.)*

This ADR does not supersede any of ADR-001's numbered decisions. `Part of: #n` and
`Blocked-by: #n` are the *mechanism* ADR-001 D3.2 and D3.5 were written against — the
dependency rule ("cancelling never satisfies a dependency") and the re-opening rule
("a returning idea is a new issue") — not the decision text itself. Swapping the
mechanism leaves both rules exactly as stated; there is no `D`-heading here to point at,
and a reader should not go looking for one.

## Context

`/t-open` has, since ADR-001, recorded issue relationships as plain body text: an
inline `Part of: #n` or `Blocked-by: #n` line, or the same fact under a `### Part of` /
`### Blocked by` heading when a hand-filled issue form renders it that way. A tracking
issue's child list is a hand-ticked Markdown checklist (`- [ ] #25 …`) that `/t-ship`
edits directly. Every consumer of this data — `/t-work`'s blocker gate, `/t-cancel`'s
dependent sweep, `/t-status`'s blocked/initiative views — greps issue bodies for these
patterns, in both marker shapes, across every open issue.

GitHub now offers the same relationships natively: sub-issues (`gh issue create
--parent`, giving a child a real `parent` field and a parent a `subIssues` /
`subIssuesSummary` field with counts), and issue dependencies (`gh issue edit
--add-blocked-by`, giving `blockedBy` / `blocking` fields). These did not exist when
ADR-001 was written. They are structured, queryable without body-text parsing, and
render as first-class UI in the tracker — the body-marker convention was always a
workaround for their absence, not a design goal in itself.

## Decision

Replace the body-marker convention with native tracker relations, for the two
relationships GitHub models natively:

- **Parent/child (`Part of:` between a task and its tracking issue)** becomes a native
  sub-issue link: the tracking issue is the parent, each task is a sub-issue. Progress
  reads from `subIssuesSummary` instead of counting hand-ticked checkboxes.
- **Ordering (`Blocked-by:`)** becomes a native issue dependency: the blocked issue's
  `blockedBy` field, the blocker's `blocking` field.

Four things the issue asked to decide explicitly stay unchanged, because the native
features do not replace what they do:

- **`Split from: #n` stays body text.** GitHub has no native "split from" relation —
  a sub-issue link means parent/child, and a dependency means ordering, neither of
  which is what "this issue was carved out of that one" means. There is nothing native
  to move it to.
- **The two-working-levels rule (initiative → task) stays.** Sub-issues technically
  allow arbitrary nesting depth, but the workflow still recognizes exactly two levels:
  an `initiative`-labeled tracking issue and its task sub-issues. A task does not get
  its own sub-issues under this ADR; deeper nesting is a capability the tracker offers
  that this workflow declines to use.
- **A cancelled blocker remains abandoned, not satisfied.** ADR-001 D3.2's rule is
  unchanged: `/t-work`'s blocker gate must still treat a blocker closed as
  not-planned/cancelled as unresolved. What changes is only where the gate reads the
  fact — the native `blockedBy` field plus the blocker's `stateReason`, instead of
  parsing a body-text `Blocked-by:` line and then checking the referenced issue's state.
- **The `initiative` label stays.** `subIssuesSummary` gives a tracking issue's
  *progress*, not its *identity* — `tracker:list-initiatives` still needs a cheap way to
  find which open issues are tracking issues at all, and the label is that key.

## Rationale

- **Structured data beats body-text parsing.** A native field is queried directly;
  a body marker requires grepping every open issue's text in two shapes (inline and
  issue-form heading) and has silently misparsed before (the whole reason `/t-work`
  Phase 1 step 2 spells out both shapes today). Moving to native fields deletes that
  dual-shape parsing everywhere it exists, not just in one skill.
- **The tracker's own UI already shows these relations** once they are native —
  progress bars, linked-issue panels — which body markers never rendered as anything
  but plain text. The workflow gains that view for free.
- **Not every relation this workflow needs has a native equivalent**, so this ADR
  replaces only the two that do (`Part of:`, `Blocked-by:`) and leaves `Split from:` and
  the `initiative` label exactly as they are — matching only what the tracker actually
  models beats forcing everything into native fields or keeping everything as text.
- **The trade-off is real and worth naming plainly: resilience.** An issue body is
  captured whole by any export that reads issue text (`git log`-adjacent, a body dump).
  A native relation lives in tracker-side structured fields that a body-only export
  does not touch. Moving `Part of:`/`Blocked-by:` out of the body means
  `docs/workflow.md` §10's periodic export must be extended to also pull sub-issue and
  dependency relations, or a disaster-recovery reconstruction from that export would
  silently lose every relationship this ADR moves. This ADR does not perform that
  edit — §10 is outside its Scope — but a decision that creates a gap without saying so
  is worse than making the trade-off at all; #29 (this ADR's adoption task) carries
  `docs/workflow.md` in its own Allowed paths for exactly this reason.
- **No migration code belongs in the template.** Converting an existing repository's
  body markers into native relations is a one-time, tracker-side act specific to that
  repository's current open issues, not a capability every future template user needs
  shipped by default. A one-off script run against the tracker is the right shape for
  that; template code is not.

## Alternatives considered

- **Keep body markers, add native relations alongside them as a second, redundant
  copy** — rejected: two sources of truth for the same fact drift, and a workflow whose
  own constitution (§1.3) says nothing binding lives only in a body or thread should not
  choose to maintain a fact in two places when the tracker will hold one authoritatively.
- **Move every relation to native fields, including `Split from:`** — rejected: GitHub
  has no native field that means "carved out of," and forcing it into a dependency or
  sub-issue link would misrepresent the relationship to anyone reading the tracker's UI.
- **Drop the `initiative` label now that `subIssuesSummary` exists** — rejected: the
  summary is progress on an already-identified parent; nothing native marks *which*
  issues are tracking issues in the first place, so the label is still the cheapest way
  to answer that question at listing time.
- **Allow sub-issues to nest past two levels, matching what the tracker permits** —
  rejected: the workflow's two-level shape (initiative, task) is a decision about how
  work is planned and reported, not a limitation the previous body-marker mechanism
  happened to impose; the tracker gaining deeper nesting is not a reason to use it.

## Consequences / revisit triggers

Accepted knowingly: the resilience gap above exists the moment this ADR is adopted and
persists until #29 lands both the skill changes and the §10 export update — a task
worked between those two points has its relations only in tracker-side fields, not in
any exported snapshot. This ADR's Non-goals deliberately exclude fixing that gap here;
#29 is where it closes.

Any of these reopens this decision, as a new ADR:

1. **GitHub adds a native relation type covering `Split from:`** — re-evaluate whether
   it should move off body text.
2. **The periodic export (`docs/workflow.md` §10) cannot practically capture sub-issue
   and dependency relations** (API limits, rate limits, format mismatch) — the
   resilience trade-off named above would then be unacceptable rather than merely
   noted, and the decision needs revisiting rather than just the export mechanism.
3. **A future backend adopted via `docs/adapters/TRACKER.md` has no equivalent to
   sub-issues or dependencies** — the relation operations this ADR motivates (#29's
   `set-parent`, `add-blocker`, etc.) would need a body-marker fallback for that
   backend specifically, reopening whether the native-only approach is backend-neutral.
4. **A third working level is needed** (a task with its own sub-issues) — the
   two-working-levels rule stays only as long as nothing forces a deeper hierarchy.
