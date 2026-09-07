# Model adapter

The workflow's model-resolution reference for `/t-review`'s spawned subagent reviewer.
Skills never hardcode a model name or provider: `/t-review` step 1 resolves a plain
string — from `AGENTS.md`'s §Reviewer model slot, or an explicit override named on the
invocation — and this file names, for the **active harness**, a few example
identifiers and how that string actually turns into a subagent spawn under that model.

```
active-harness: claude-code
```

Swapping harnesses means editing this file only — no skill changes.

## What this is not

**Never an enumerated or validated list.** Both `AGENTS.md`'s slot and an inline
`/t-review` override accept **any string** — resolution is attempted by whatever spawns
the subagent, never checked against anything named here. This document exists to help a
human pick a sensible value by example, not to gate what values are accepted.

A hardcoded "pick one of these known models" picker was considered and rejected while
planning issue #155: it cannot generalize past a harness with a small, stable model
family to a harness like OMP (omp.sh), which does model-agnostic, role-based routing
across 60+ providers with no fixed set to enumerate against. Whatever this document
lists for the current harness is illustrative only, and goes stale the moment that
harness's own lineup changes — nobody should read an omission here as a rejection, and
nothing here is re-validated by any script or gate.

## Current harness: Claude Code

`/t-review`'s spawned subagent is a Claude Code `Agent` tool call; whatever string
resolves from `AGENTS.md`'s slot or an inline override is passed straight through as
that tool's `model` input. Example values accepted today: the short names `sonnet`,
`opus`, `haiku`, `fable`, or a full model id such as `claude-sonnet-5`, `claude-opus-5`,
`claude-haiku-4-5-20251001`, `claude-fable-5-1`. This list is illustrative and not
exhaustive — whatever Claude Code's own `Agent` tool currently accepts for `model` is
authoritative, not this document.

## Resolution: string to spawn

1. `/t-review` resolves the plain string per the precedence `AGENTS.md` §Reviewer model
   and `.claude/skills/t-review/SKILL.md` step 1 document: an explicit invocation
   argument, then `AGENTS.md`'s slot, then no override at all.
2. That string is handed, unmodified and unchecked, to whatever this harness uses to
   spawn a subagent under a chosen model — the `Agent` tool's `model` field, for Claude
   Code.
3. A string the harness's own spawning mechanism does not recognize fails exactly the
   way any other bad `model` argument to that mechanism fails today. This document adds
   no new validation layer, and neither does `/t-review`.

## Revisit triggers

- A different harness becomes active — replace §Current harness's examples and
  §Resolution's spawn step with that harness's own equivalents; `active-harness:` above
  changes to name it, the same way `docs/adapters/TRACKER.md`/`FORGE.md` name their own
  active backend.
- Claude Code's own accepted model identifiers change — update the example list; this
  is documentation only, never a gate, so nothing else here needs to change.
- A harness is adopted whose model resolution needs more than a plain string (e.g. a
  role name resolved server-side, as OMP's routing does) — resolved by adding that
  harness's own §Current harness section alongside this one, never by constraining the
  string's shape for every harness to fit the narrowest one.
