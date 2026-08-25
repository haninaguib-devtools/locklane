# 8 — Write an ADR ratifying the rewrite's stack and agent model
Issue: #8 · Part of: #1

## Asked
Ratify, as a merged ADR, the stack and agent-model decision already made for the
locklane rewrite (initiative #1): Spring Boot engine + Angular PWA client + SQLite for
durable non-binding state, with an agent-per-worktree persistent PTY model (not a job
queue) — a client reattaches to a live terminal session from any browser, at any time,
so an agent keeps running when the laptop closes and stays reachable from a
home-server-hosted deployment.

Tauri (native PTY access, no server process) was considered and rejected: it cannot
satisfy "keeps running after the laptop closes, reachable from anywhere," which forces
a server + web client architecture and rules out any desktop-only alternative.

`CONSTITUTION.md` §4 ("Stack & architecture") is currently reserved. Per
`CONSTITUTION.md` §1.3, nothing is durably decided until it is merged — today this
decision lives only in issue bodies, not in anything binding.

This does not block implementation: #5 (PTY session core) and its siblings do not need
this ADR merged first and can proceed in parallel — no `Blocked-by` relationship in
either direction.

## Done when
- `docs/adr/002-<slug>.md` exists (numbered after `000-template.md` and
  `001-phase0-delivery-workflow.md`), following the template's shape: Context,
  Decision, Rationale, Alternatives considered (Tauri, rejected, with the reason
  above), Consequences/revisit triggers (e.g. hosting requirements changing, a second
  maintainer joining).
- `CONSTITUTION.md` §4 has the reserved placeholder replaced with a one-line operative
  rule pointing at the new ADR.
- `scripts/consistency-check.sh` passes (cross-artifact document consistency).

## Explicitly not
- Any change to the delivery workflow itself (ADR-001's territory) — untouched.
- Implementation of the stack — that is #5/#6/#7/#3/#4; this task only ratifies the
  decision, in writing.

## Decisions made along the way
- none

## Deviations / notes
- none
