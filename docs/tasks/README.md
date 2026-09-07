# Task records

One file per task, `./<bucket>/<issue-id>-<slug>.md` — `<bucket>` is the task ID rounded
down to the nearest 100, zero-padded to 6 digits (task 142 → `./000100/`) — created by `/t-work` from
`TEMPLATE.md` **on the task's branch** and merged atomically with the change it
describes. The record captures what the diff cannot: intent, exclusions, and the
decisions and deviations that shaped the work.

- The record is part of the diff — `/t-review` checks that it honestly describes the
  change.
- After work starts, task intent changes here (in the diff), never in the issue body.
- There is no post-merge closeout: the record merges when the code merges.
- A record's `## Origin` section is always present but usually reads "none" — it
  carries an optional external origin forward from the issue, or names the parent
  initiative that carries one instead, per `docs/architecture/external-origin.md`
  (ADR-010).
- `## Verification` (`docs/architecture/verification.md`) holds one entry per the
  plan's `verification:` list, or `none`. A record with no such entries — every task
  before this convention existed, and most after — needs no migration: the section
  reads `none` and every gate that looks at it finds nothing to check.
- `## Feedback` (`docs/architecture/feedback-pass.md`) holds one entry per feedback
  pass — the evidence reference, its classification, and what changed in response —
  or `none`. A record with no such entries — every task before this convention
  existed, and any task nobody has sent feedback to since — needs no migration.
