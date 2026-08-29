# ADR-006: Automatically remove a console-created worktree once its issue closes, clean and unattached — a narrow ADR-005 carve-out

**Status:** Accepted · 2026-08-29
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–005 if a
second maintainer joins — workflow §13 Q9.)*

## Context

ADR-005 retired `/t-clean` and decided that a stale local worktree or branch is left
alone **permanently** — cleaned up by hand only, with nothing automated, because no
operator had ever actually reached for a cleanup skill and "leaving a stale worktree or
branch alone costs nothing until something needs that path back." That reasoning was
about a developer's own deliberately-created pipeline worktree (`/t-work`'s own
checkout): rare to create, easy to notice, and trivially removed by hand the moment it
is actually in the way.

This application (`CONSTITUTION.md` §4) also creates worktrees, but for a different
reason and a different consumer: every time a person opens a console against an issue
in the product itself, `WorktreeCreationService` runs a real `git worktree add` for
that issue, one per project's issue. This happens as ordinary product use, not as a
deliberate pipeline step — a user pressing "Console" has no reason to know a worktree
now exists on disk, let alone that it should eventually be removed by hand. Unlike
ADR-005's target, there is no human touchpoint (a colliding branch name, a `/t-clean`
run) that would ever surface the need to clean one up. Left fully to ADR-005's rule,
these accumulate indefinitely — exactly the "disk space" condition ADR-005 itself named
as a reason a person might reach for manual cleanup, except here nobody ever does,
because nobody is ever prompted to.

## Decision

Add a periodic sweep (`WorktreeCleanupSweeper`, #319) that automatically removes a
console-created, per-issue worktree once, and only once, all three hold, re-checked
fresh at sweep time:

1. its GitHub issue's cached state (`GhIssueCache`) is `CLOSED`;
2. `git status --porcelain` in the worktree is empty;
3. no live console/PTY session (`SessionRegistry`) has a working directory inside it.

A worktree failing any one of these — issue not found in the cache, issue still open,
dirty, or attached — is left untouched, never force-removed. This is a narrow, named
exception to ADR-005, not a reversal of it: **ADR-005's rule continues to govern every
worktree that is not a console-created, per-issue worktree meeting all three
conditions above** — in particular, a developer's own pipeline worktree (`/t-work`'s
checkout) and a console-created worktree that is still open, dirty, or attached are
still left alone permanently, removed by hand only, exactly as ADR-005 says.

## Rationale

- **Different origin, different cost profile than what ADR-005 was about.** ADR-005
  measured a developer's own rarely-created, easily-noticed pipeline worktree. A
  console-created worktree is created automatically by ordinary product use, with no
  equivalent moment that would ever prompt a person to clean it up by hand — it is
  exactly the accumulation ADR-005's own "disk space" language anticipated, for a
  worktree-creator ADR-005 was not written with in mind.
- **Safety makes the automation a formality, not a risk.** The three-part guard only
  ever deletes a worktree that is already both provably done (issue closed) and
  provably safe (clean, unattached) — the same criteria a careful human doing manual
  cleanup would apply, checked mechanically and on a schedule instead of relying on
  someone remembering to. `CONSTITUTION.md` §1.5 forbids weakening any one of the three
  to make the sweep simpler or faster.
- **Scoped, not general.** Naming this ADR as a carve-out and referencing ADR-005
  explicitly keeps ADR-005's own rule fully intact everywhere it was ever meant to
  apply; this ADR narrows nothing about ADR-005 itself, it adds one specific, guarded
  exception next to it.

## Alternatives considered

- **Extend ADR-005's manual-only policy to console-created worktrees too** — rejected:
  nothing in the product ever prompts a user to clean up a worktree they may not even
  know exists, unlike a developer's own pipeline checkout.
- **A weaker guard (issue-closed alone, no clean/unattached check)** — rejected: risks
  deleting uncommitted work or removing a running console's own directory out from
  under it; forbidden by `CONSTITUTION.md` §1.5.
- **Manual-only "run cleanup now" trigger, no schedule** (letting a future on-demand
  control, #320, be the only way to invoke it) — rejected: defers cleanup indefinitely
  to a human visiting a page they may never open; the schedule is what actually solves
  the disk-accumulation problem, with an on-demand trigger as a convenience alongside
  it, not a replacement for it.

## Consequences / revisit triggers

Accepted knowingly: a worktree that is closed-but-dirty or closed-but-attached can sit
indefinitely with no reminder to a person that it would become eligible once cleaned up
by hand — the sweep only ever acts when already safe, it never nags.

Any of these reopens this decision, as a new ADR:

1. **The sweep removes a worktree it should not have** — a guard condition proves wrong
   in practice. The single most serious trigger; suspend the sweep and re-derive the
   guard rather than patching around a near-miss.
2. **The fixed interval proves wrong** — worktrees pile up faster than the sweep clears
   them, or nothing benefits from checking more often than issues actually close.
   Retuning the interval alone is an ordinary change; only a change to the guard's
   shape needs a new ADR.
3. **A new worktree-creation path this guard was not written for appears** — e.g. a
   project console getting its own worktree (#314) — and needs its own eligibility
   rule, distinct from the per-issue one this ADR covers.
