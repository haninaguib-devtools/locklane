# ADR-007: A solo drive defers on pending CI instead of invoking `/t-ship`

**Status:** Accepted · 2026-08-30
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–006 if a
second maintainer joins — workflow §13 Q9.)*

This ADR amends one sentence of ADR-006 D3. Every other decision in ADR-004 and ADR-006
— the integration branch, the per-child merges, the exclusion policy, the aggregate PR,
the solo sequence's two protected-path tests, the one bounded retry, tracker-write
authority — stands untouched.

## Context

Issue #88. ADR-006 D3 says the solo mode "does not end by naming `/t-ship` — it invokes
`/t-ship <id>` and pauses at that stage's existing merge-confirmation gate," always.
`/t-ship`'s own precondition 3 requires CI green before that gate can be reached; if CI
is still running rather than concluded, invoking `/t-ship` anyway lands the run inside a
precondition that cannot yet be satisfied, with nothing useful to report and no human
present to notice — chained by `/t-drive`, there is no one watching the terminal the way
a human invoking `/t-ship` by hand would be. That case is explicitly fine on its own
terms ("the human chose the moment"); it is only a problem once nothing chains the wait
to a human's presence.

## Decision

`/t-drive`'s Solo mode gains one new step, immediately before it invokes `/t-ship`: read
the task's PR checks once. If any check is still queued or in progress — genuinely
unsettled, not failed — the run stops there, **without invoking `/t-ship` at all**,
names the pending check(s) plainly, states this is not a failure, and names
`/t-ship <id>` as what to run once CI has concluded. No CI configured, or every check
already concluded (green or red), continues into `/t-ship` exactly as ADR-006 already
specifies — a concluded failure is decisive information `/t-ship`'s existing
precondition 3 already reports, not a wait.

The check is a single, one-time look, never a poll-and-wait loop with a timeout to
calibrate: "bounded time" is satisfied by construction, not by picking a duration.
`/t-ship` itself is not touched by this decision — same wording, same preconditions,
same refusals, same confirmation, whether invoked by a human directly or reached by a
solo drive whose CI had already settled.

**"The run stops once" still holds, restated:** a given solo-drive invocation now
reaches **exactly one** of two stopping shapes — the merge-confirmation gate (CI had
already settled) or this new pre-gate stop (CI had not) — never both in the same run.
What moves is not the count but the location, and the location is decided by a fact
(CI status) knowable before `/t-ship` is invoked, the same kind of pre-invocation fact
D2's two protected-path tests already gate on.

## Rationale

- **Fixing this in `/t-drive` rather than in `/t-ship` keeps the gate itself provably
  unchanged.** A human invoking `/t-ship` directly experiences exactly what they always
  have; nothing about its preconditions, wording, or confirmation behavior depends on
  whether a solo drive called it. The alternative — teaching `/t-ship` to wait and
  report when it gives up — would touch the one stage every path to `main` shares,
  for a problem that only exists when nothing chains the wait to a human's presence.
- **A single look is honest about what "bounded time" means here.** Inventing a
  wait-then-give-up loop needs a duration to defend and re-tune; checking once needs
  neither. If CI is not done, the correct next step is unconditionally "wait for CI,
  then run `/t-ship <id>`" regardless of how long that takes — a loop would only ever
  arrive at the same instruction, slower and with a number to justify.
- **Reusing the initiative mode's own "stop and name the next command" shape** costs no
  new machinery and no new failure mode to test — `/t-drive` already does this exactly,
  every time an initiative's aggregate review comes back `not-ready`.
- **The Non-goal that a red or missing CI result must not become acceptable to ship on**
  is preserved by construction: this decision only ever intercepts the *pending* state.
  A failure chains into `/t-ship` unchanged, so its refusal is exactly as immediate as
  it is for a human running `/t-ship` by hand.

## Alternatives considered

- **`/t-ship` itself waits, says so, and returns control when it gives up** (the issue's
  other named option) — rejected: requires touching the one stage every merge path
  shares, and requires picking and defending a timeout; the done-when's own "`/t-ship`'s
  gate is unchanged" is only reachable with certainty by leaving it alone entirely.
- **Poll CI in `/t-drive` with a bounded retry, mirroring `/t-ship` precondition 4's
  short mergeability retry** — rejected: precondition 4's retry is a few seconds three
  times, sized for an already-fast, already-converging asynchronous answer; CI can take
  minutes, so the equivalent here would be a real wait with a real duration to justify —
  the same objection as the previous alternative, one layer down.
- **Leave ADR-006 D3 as an absolute and treat this as `/t-ship`'s problem alone,
  unresolved** — rejected: the issue exists precisely because the current unattended
  behavior is undesirable, not because no fix belongs anywhere.

## Consequences / revisit triggers

Accepted knowingly: a solo drive whose CI is still running when `/t-drive` reaches
this step ends without a merge in flight, on the same footing as a solo run that
stopped without shipping (D4) — open, unmerged, untouched, for an ordinary human
pickup by re-running `/t-ship <id>` once CI concludes.

Any of these reopens this decision, as a new ADR:

1. **CI on this project routinely takes long enough, or completes unpredictably enough,
   that a single look almost always finds it pending** — the one-look design stops being
   useful in practice; revisit toward a bounded wait or a different signal entirely.
2. **A human routinely re-runs `/t-ship <id>` moments after a deferred solo drive, CI
   having settled in the meantime** — a sign the single look is too eager and a short
   wait would have avoided a second invocation for free.
3. **The initiative mode's ending is aligned to chain into `/t-ship`'s gate**
   (ADR-006 revisit trigger 5) — if that happens, this decision's asymmetry with the
   initiative mode's existing "stop and name" shape may need re-deriving from whichever
   ending the initiative mode adopts.
