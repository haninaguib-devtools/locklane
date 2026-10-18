# 1009 — Keep the service running when the kernel kills one process for memory (OOMPolicy=continue)
Issue: #1009

## Asked
On 2026-10-18 the OOM killer killed one agent's 15 GB build JVM inside
`locklane.service`; systemd's default `OOMPolicy=stop` then stopped the whole unit —
engine, every session, every background agent. Add `OOMPolicy=continue` to the
`[Service]` section of the systemd unit `scripts/locklane` writes, so the kernel still
kills the runaway process but the engine and other sessions keep running.

## Done when
- The unit `write_systemd_unit` writes carries `OOMPolicy=continue` alongside
  `SuccessExitStatus=143` and `Restart=always`.
- Existing installs pick it up on update.
- launchd is untouched (no unit-wide stop there).

## Explicitly not
- Resuming or flagging sessions that were running when the engine stopped
  unexpectedly — the issue's "Related, possibly separate" section; proposed as its own
  issue, not done here.

## Decisions made along the way
- Existing installs need no separate migration: `locklane update` already calls
  `register`, which rewrites the unit through `write_systemd_unit` and runs
  `systemctl --user daemon-reload` (`scripts/locklane` `update_cmd` → `register_cmd`).
  So the next update replaces the unit with one carrying the new line; no drop-in is
  written. A hand-written drop-in from the workaround
  (`locklane.service.d/oom-policy.conf`) is harmless alongside it and can be removed.
- Engine OOM is unchanged: if the engine JVM itself is killed, the unit's main process
  exits and `Restart=always` restarts it as before.
- systemd older than 243 does not know `OOMPolicy` and ignores the line with a log
  warning; the unit still loads.
- Added a lifecycle-harness scenario, `systemd-unit-oom-policy-continue`, asserting the
  registered unit carries `OOMPolicy=continue` (and still `Restart=always`).

## Deviations / notes
- Verified the unit text with `systemd-analyze --user verify` (placeholder paths
  substituted): no complaint about `OOMPolicy`.
- Not exercised against a real OOM kill; the stub harness only checks the unit text.

## Agents
- work: claude-code / claude-opus-5-5
