# External origin — shape and rules

**Status:** binding.

The machine-readable shape of the optional origin ADR-010 §D1 names, and where it may
and may never appear. Implements ADR-010 for `/t-open`, `docs/tasks/TEMPLATE.md`, and
the draft PR `/t-work` opens; every other rule about what an origin may and may never do
lives in ADR-010 itself and is not restated here.

## The shape

Exactly two fields, both required together or the whole section is absent:

```
## Origin
system: <short name of the external system — free text, e.g. "Proposarium">
url: <a durable URL for that system's proposal or discussion>
```

- **Optional.** No section at all is the default and the common case; a task or an
  initiative with no origin behaves exactly as it always has (ADR-010 §D1, issue #141
  Done-when).
- **Both fields or neither.** `system` with no `url`, or the reverse, is an incomplete
  origin — treated as **no origin at all**, never written or carried forward
  half-formed. This is the entire "invalid data fails safe" rule: nothing downstream
  ever sees a malformed shape, because nothing upstream ever writes one.
- **A link, never a fetch target (ADR-010 §D7).** `url` is recorded and displayed
  exactly as given. No skill, script, or check in this repository fetches it, checks
  that it resolves, or treats its reachability as a signal of anything. A stale or
  dead link leaves the origin exactly as informative as a live one: none, for a
  gate's purposes.
- **Never authoritative (ADR-010 §D1/§D3).** An origin sits alongside Goal / Done when
  / Scope / Non-goals, never inside them. No gate script may read `system` or `url` as
  a pass/fail condition, and no origin ever supplies acceptance criteria, scope, or
  authorization a human did not separately state in the issue body itself.

## Where it lives

| Surface | Carries |
|---|---|
| A standalone task's issue body, or an initiative's issue body | Its own `## Origin` section, written once at `/t-open` time (`.claude/skills/t-open/SKILL.md`) — never edited afterward by any skill; a human may still hand-edit the issue, same as any other section. |
| A task issue that is a child of an initiative (has a `parent`) | No `## Origin` section of its own. It refers to the initiative instead — the initiative's own Origin, if any, covers every child (ADR-010 §D5); duplicating a second origin onto the child would create a second, possibly conflicting, source for the same fact. |
| The task record (`docs/tasks/<bucket>/<id>-<slug>.md`, from `docs/tasks/TEMPLATE.md`) | An `## Origin` section, always present (mirroring the existing "or none" convention `## Decisions made along the way` and `## Deviations / notes` already use): the issue's own `system: <name> / url: <url>` when the issue carries one; `Inherited from initiative #<tracking> — see its own Origin.` when the issue's `parent` names an initiative that carries one; `none` otherwise. Filled by `/t-work` Phase 1 step 6 when the record is created, from the issue read in Phase 1 step 1 (and the parent issue's body, when the task has one). |
| The draft PR `/t-work` opens | The same line the record carries, one line near the top of the PR body (below the tracker's auto-close phrase, above "what changed") — so a reader never has to open the record to see where the work originated. |
| The squash-merge commit | Nothing new. The task record is part of the merged diff (`CONSTITUTION.md` §1.3); its `## Origin` line is what remains authoritative for "where this came from" after merge — no separate copy in the commit message is needed or added. |

## Why not a third hierarchy level

ADR-010 §D5 keeps the hierarchy at exactly two levels (initiative → task,
`CONSTITUTION.md` §1.2, ADR-003). An origin is metadata *on* whichever of those two
levels already exists — never a level of its own, and never duplicated onto both ends
of the same initiative/child relationship at once.

## Revisit triggers

- A second external system needs a shape this two-field grammar cannot express (see
  ADR-010's own revisit triggers) — resolved by amending this document, not by a
  provider-specific special case in a skill.
- `#146`'s preview adapter contract or `#143`'s observer contract need to read an
  origin's fields directly — they read this document's shape rather than inventing
  their own.
