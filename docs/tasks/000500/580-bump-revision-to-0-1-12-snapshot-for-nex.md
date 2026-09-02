# 580 — Bump revision to 0.1.12-SNAPSHOT for next development cycle
Issue: #580

## Asked
Bump `<revision>` to `0.1.12-SNAPSHOT` for the next development cycle, now that
v0.1.11 is released.

## Done when
`pom.xml`'s `<revision>` reads `0.1.12-SNAPSHOT`.

## Explicitly not
- Does not itself cut or dispatch anything — v0.1.11 already shipped.

## Decisions made along the way
- none

## Deviations / notes
- none
- `./mvnw -B test` on this machine fails the same three pre-existing, environment-specific
  engine tests as #574/#575/#578 (host `credential.helper=osxkeychain` and live worktree
  state), plus intermittently `SessionRegistryResumeCaptureTest.closingTheConsoleKeepsItsCapturedResumeId`
  (a 5-second `waitUntil` that failed twice then passed on a third isolated run) —
  timing flakiness under this machine's load, not caused by this one-line `pom.xml`
  change. CI is the clean run.
