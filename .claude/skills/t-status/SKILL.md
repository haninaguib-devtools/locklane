---
name: t-status
description: Read-only pipeline overview — parents, open tasks with blockers, branches, PRs, checks, and review verdicts, plus warnings. Use when asked what is in flight or what to pick up next.
---

# Status

Run `.t-workflow/scripts/snapshot.sh status` and relay it, in plain language first:
what is ready to ship, what is waiting on a review or a blocker, what has a branch but
no PR, which initiatives have an integration branch or PR, and each warning. Recommend what to pick up next and name its command. Change
nothing; write nothing to the tracker.
