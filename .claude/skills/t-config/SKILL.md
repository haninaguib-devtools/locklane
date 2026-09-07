---
name: t-config
description: Set this repo's default `/t-review` reviewer model without hand-editing AGENTS.md — locates the reviewer-model slot, prompts for a model name (or to clear the override), and writes the answer into that slot alone. Use to configure, set, or change the default reviewer model.
allowed-tools: Read, Edit, Bash
---

# Configure the default reviewer model

A guided editor for one existing slot — `AGENTS.md` §Reviewer model
(`docs/architecture/local-slots.md`), which `/t-review` step 1 already reads. This adds
no new config file and no new precedence rule; it only spares a human from finding the
slot and its exact marker syntax by hand.

Run with no arguments.

## Procedure

1. **Locate the slot.** Read `AGENTS.md`'s "## Reviewer model" section and its
   `<!-- local -->` … `<!-- /local -->` markers. Missing entirely — a repo that
   predates that section, or a malformed file — stop and say so; there is nothing to
   configure.
2. **Show the current value** — the single line between the markers, verbatim.
3. **Offer examples, never a menu.** When `docs/adapters/MODEL.md` exists, read its
   "## Current harness" section and surface its example model identifiers as a hint
   alongside the prompt below. Missing — a repo that predates it, or hasn't filled in
   its own harness section — proceed without examples; this is convenience only, never
   a dependency the prompt requires. **Never write to this file.**
4. **Ask the human** for either:
   - a model name to set as the new default — **any string is accepted**; there is no
     enumerated or validated list to check it against (`docs/adapters/MODEL.md`'s own
     "What this is not"), so a name absent from step 3's examples is not rejected — or
   - clearing the override back to none.
5. **Write back exactly one line**, between the existing markers, and nothing else in
   `AGENTS.md`:
   - a chosen name → `Default reviewer model: <name>`
   - cleared → `Default reviewer model: (none — reviews inherit the invoking session's
     model)` — the exact placeholder `docs/architecture/local-slots.md` names, so a
     later template sync still recognizes an unfilled slot.

   Marker hygiene: only this one line changes. The heading, the precedence prose above
   it, and every other line in the file stay byte-identical.
6. **Report** the change in plain language — what the default was, what it is now, and
   that it takes effect the next time `/t-review` spawns a subagent reviewer (an
   explicit model named on a `/t-review` invocation still wins over this default,
   unchanged).

## Non-goals

Any setting beyond the reviewer-model slot. `/t-config` edits this one slot only —
generalizing it to other settings waits until a second one actually exists.
