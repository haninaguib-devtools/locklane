# Confirmation gates

**Status:** binding convention.

When a `t-*` skill needs a human's confirmation before an irreversible act — /t-ship's
merge, /t-fix's merge, /t-cancel's teardown — it ends its turn by asking. This document
is the single home for how that asking works, so every gating skill behaves identically.

## The gate

The gate is an ordinary question, asked like any other chat message — or through the
environment's native question mechanism where one exists. It contains, in this order:

- the **evidence** the person needs in order to decide (for a task merge: review
  verdict, CI state, diff summary, pending human checks; for a no-issue fix: the
  eligibility claim, CI state, diff summary; for a cancellation: what will be destroyed,
  the reason, every neighbour decision), stated in ordinary language;
- **one question**, e.g. *"Merge PR #35 into main?"*;
- the **accepted answers**, named explicitly: e.g. reply `confirm` to proceed or
  `abort` to stop.

## Rules

- Exactly **one** gate per turn, and it is the **last** thing in the message —
  nothing follows it, no closing remark, no signature.
- The affirmative option (`confirm`) is the confirmation; anything else stops the
  action, exactly as an unclear answer always has. **Never proceed on silence.**
- Everything the person needs in order to answer goes *before* the question, in prose
  — never behind a link.
