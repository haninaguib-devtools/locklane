# ADR-008: `/t-ship` watches CI attended; `/t-drive` Solo drops its own pre-look

**Status:** Accepted · 2026-08-31
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–007 if a
second maintainer joins — workflow §13 Q9.)*

This ADR **amends ADR-007**, retiring the specific mechanism its Decision introduces (a
one-look CI check inside `/t-drive` Solo mode, run immediately before invoking
`/t-ship`) while keeping the goal that mechanism served — a driven run never sits on an
unbounded, nobody-watching wait — intact, achieved a different way. ADR-007's own text
is not edited (append-only, `CONSTITUTION.md` §2.1); this file is the new decision that
supersedes its Decision section. ADR-007 itself set this same precedent, amending one
sentence of ADR-006 Decision 3 without touching ADR-006's own file. Every other decision
in ADR-004 and ADR-006 stands untouched.

## Context

Issue #113 (items 5–6). Task #113 changes when CI runs at all: `.github/workflows/ci.yml`
and `.github/workflows/review-gate.yml` now skip a draft PR entirely and start at
`ready_for_review` — the whole work/review/fix window costs zero hosted minutes, and the
one CI run that gates a merge begins when `/t-ship` marks the PR ready.

This breaks the precondition ADR-007 was written around. `/t-ship`'s CI-green check used
to be **precondition 3**, read *before* Procedure step 1 marked the PR ready — under the
old timing CI had already been running throughout the draft window (every push fired it),
so reading its status first was meaningful. Under the new timing, that same read, taken
before step 1, would see no CI run at all and misreport it as green — the read has to
move to *after* step 1, inside the Procedure. `/t-ship`'s own SKILL.md is amended (#113)
accordingly: CI-green is no longer a precondition; it is Procedure step 2, run
immediately after step 1 marks the PR ready, and it **watches** — re-reading
`forge:pr-checks <pr>` until every check concludes, interruptible, never a fixed timeout
to defend, exactly the same "bounded by a human's presence, not a number" shape ADR-007
itself argued for its own one-look design.

This, in turn, breaks the specific mechanism ADR-007 introduced. `/t-drive` Solo step 6
performed its own one-look CI check *before* invoking `/t-ship` at all, precisely so an
unattended solo drive would never invoke `/t-ship` while its (then pre-ready) CI-green
precondition could not yet be satisfied. Under the new timing, Solo step 6's look happens
*before* the PR is ever marked ready — meaning CI has not started, there is nothing to
observe, and the check would either misread "no CI configured" or need to itself mark the
PR ready to find out, which duplicates `/t-ship`'s own Procedure step 1 and violates
`/t-drive`'s own Rule against reimplementing another skill's steps. The precondition
ADR-007 was defending against reading — "CI still unsettled, nothing useful to report" —
no longer exists in that shape: it moved, along with the read that used to trigger it,
into `/t-ship` itself.

## Decision

### D1. CI-green is `/t-ship` Procedure step 2, and it watches — attended, by construction, regardless of caller

`/t-ship`'s own contract stays exactly one contract, whoever invokes it — the load-bearing
property ADR-007 itself insisted on ("nothing about its preconditions, wording, or
confirmation behavior depends on whether a solo drive called it"). So the new Procedure
step 2 watches identically whether a human typed `/t-ship <id>` directly or a `/t-drive`
Solo run chained into it: read `forge:pr-checks <pr>`; every check already concluded →
continue with no wait; any check still pending → re-read on a short interval until every
check concludes, or the session is interrupted (interrupted → stop, report the pending
checks, leave the PR ready, and say a later `/t-ship <id>` re-enters this same step); any
check concludes red → stop, put the PR back into draft, name `/t-work <id>` fix mode.
Never a fixed timeout to calibrate, matching ADR-007's own standard for "bounded time":
"the correct next step is unconditionally 'wait for CI, then run `/t-ship <id>`'
regardless of how long that takes."

### D2. `/t-drive` Solo step 6 is retired; Solo mode proceeds straight to `/t-ship`

There is nothing left for `/t-drive` to check before invoking `/t-ship`: CI has not
started (the PR is still draft) and cannot meaningfully be read, so the one-look
mechanism ADR-007 added has no input to look at. Solo step 6 is removed; the sequence
that was steps 1–5, 7 renumbers to 1–6, and the run proceeds unconditionally from the
one-bounded-retry step straight into `/t-ship <id>` — the same single stop ADR-006 D3
already named ("the solo run does not end by naming `/t-ship` — it invokes `/t-ship <id>`
and pauses at that stage's existing merge-confirmation gate"), now reached with D1's watch
folded inside it rather than short-circuited before it. **"The run stops once" still
holds**, restated once more: a solo-drive invocation reaches exactly one stopping shape —
either D1's red-CI stop (a concluded failure, decisive, exactly as `/t-ship`'s old
precondition 3 reported one) or the merge-confirmation gate — never both, and both now
live inside `/t-ship` itself rather than split across a `/t-drive` pre-check and a
`/t-ship` gate.

### D3. Why an unattended solo drive may enter an attended-style watch without recreating the problem ADR-007 solved

ADR-007's one-look design existed to bound a wait that could otherwise be arbitrarily
long, with nobody present to notice it had settled. That risk does not return here,
because the same task that forces this amendment also removes its cause: items 1, 3, and
5 of #113 collapse CI from seven jobs billing ~7 minutes with an unpredictable pending
window down to one job with a `timeout-minutes: 10` ceiling and an expected real duration
on the order of one minute. The watch D1 describes is therefore bounded twice over —
first by the workflow's own hard timeout (a hang concludes as a failure inside 10 minutes,
never GitHub's 6-hour default), second in practice by how little work the job now does.
This is exactly the condition ADR-007's own revisit trigger 1 named as grounds to move
"toward a bounded wait": CI on this project no longer "routinely takes long enough...
that a single look almost always finds it pending." "Attended" in D1 describes who
eventually reads the answer — always a human, whether they typed `/t-ship` themselves or
a solo drive's report reaches them once the gate is answered or the watch stops on a red
check — not that a human must be actively watching a terminal for the few tens of seconds
the watch typically runs. A poll bounded by a ten-minute worst case is a different kind of
wait than the open-ended one ADR-007 was written against.

## Rationale

- **The gate itself stays provably unchanged**, honoring ADR-007's own load-bearing
  argument for keeping `/t-ship` untouched by caller: this decision changes *what*
  `/t-ship` checks and *when* (moving CI-green from precondition to Procedure, per #113's
  own timing change), never *whether* it behaves differently for a human versus a driven
  caller. A human running `/t-ship <id>` by hand experiences exactly the same watch a
  solo drive's chained invocation does.
- **Retiring Solo step 6 instead of teaching it to poll pre-invocation** keeps the fix in
  one place. `/t-drive`'s own Rules already forbid reimplementing another skill's steps;
  giving Solo step 6 a new, different pre-check (rather than removing it) would recreate
  the split ADR-007 introduced, for a condition (CI not yet started) that is now always
  true at that point in the sequence — a check that always yields the same answer is not
  a check, it is dead code.
- **The timeout-minutes bound is what makes chaining into an attended-shaped watch safe
  for an unattended caller.** Without items 1/3/5 of #113 shrinking CI's real duration and
  capping its worst case, this amendment would recreate exactly the risk ADR-007 was
  written to prevent; the two changes are accepted together for that reason, not
  independently.
- **Restating "the run stops once" explicitly** rather than trusting it to fall out of the
  mechanics keeps the invariant legible to a future reader who has not traced through
  both `/t-ship`'s and `/t-drive`'s full text to confirm it still holds — ADR-007 set
  this same precedent for its own amendment of ADR-006.

## Alternatives considered

- **Keep Solo step 6, teaching it to mark the PR ready itself before checking CI** —
  rejected: duplicates `/t-ship` Procedure step 1, which `/t-drive`'s own Rules already
  forbid ("nothing here substitutes for ... `/t-ship`'s own contract — call them exactly
  as documented, never reimplement their steps"); also means a `/t-drive` Solo run could
  mark a PR ready and then stop without invoking `/t-ship` at all, leaving a ready PR with
  no gate reached — a new, worse failure mode.
- **Give `/t-ship`'s watch a caller-conditioned shape — poll when invoked by a human,
  single-look-and-defer when chained from `/t-drive`** — rejected: reopens exactly the
  property ADR-007 fought to preserve, that `/t-ship`'s own behavior never depends on who
  called it; also requires threading caller identity through a skill invocation, machinery
  this pipeline has avoided everywhere else (skills read state, never a passed-in "mode"
  flag beyond the documented Normal/Fix split).
- **Leave `/t-ship`'s CI-green as a true precondition, reordering nothing, and instead
  make `ci.yml`/`review-gate.yml` still run something minimal on a draft PR so a
  precondition-time read has anything to see** — rejected: reintroduces exactly the
  billed-minutes cost #113 exists to remove; the entire point of items 1–5 is that the
  draft window costs zero hosted minutes.
- **Bound `/t-ship`'s watch with its own explicit timeout, independent of the workflow
  files' `timeout-minutes:`** — rejected: a second number to calibrate and keep in sync
  with the workflow's own bound is exactly the "duration to defend" ADR-007 rejected for
  the equivalent case; the workflow's own ceiling is the one number that needs setting,
  and it is set once, in the workflow file itself (#113 item 3).

## Consequences / revisit triggers

Accepted knowingly: a `/t-drive` Solo run that reaches `/t-ship` now spends real
wall-clock time inside the watch when CI has not yet concluded — bounded by the
workflow's own `timeout-minutes:`, typically much shorter, but no longer literally
instantaneous the way the old pre-ready precondition read (or ADR-007's one-look) was.
This trades a small amount of session time for one fewer stop-and-resume round trip on
the common case where CI concludes quickly.

Any of these reopens this decision, as a new ADR:

1. **CI's real duration or reliability regresses** — a stack gets added (`AGENTS.md`
   §Checks item 1) and pushes real per-PR duration back up toward what made ADR-007's
   one-look design necessary in the first place; revisit toward re-introducing a bounded
   pre-check, or raising `timeout-minutes:` deliberately rather than by drift.
2. **A driven solo run routinely times out `/t-ship`'s watch** (hits the ten-minute
   ceiling rather than concluding quickly) — a sign the watch's bound is too loose in
   practice for an unattended caller specifically; revisit toward a shorter cap for a
   chained invocation, if that can be done without reintroducing caller-conditioned
   `/t-ship` behavior (the alternative rejected above).
3. **The initiative mode's ending is aligned to chain into `/t-ship`'s gate** — ADR-006
   revisit trigger 5, restated here from ADR-007. If that happens, this decision's watch
   (D1 above) becomes the shape the initiative mode's own aggregate-PR ship inherits
   too, worth stating explicitly rather than assuming.
