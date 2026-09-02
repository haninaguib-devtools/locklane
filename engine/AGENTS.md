# engine/ — session-start addendum

Read `AGENTS.md` and `CONSTITUTION.md` at the repo root first; this file adds one
engine-specific rule that applies whenever you read or edit a file under this
directory.

## Logging

Every error or degraded outcome in the engine is logged, with its cause, at the level
its meaning warrants. In short: a `catch` block logs (exception as the log call's last
argument), rethrows to a caller that logs, or carries a `// silent: <why>` comment and
logs nothing above `DEBUG` — never a silent swallow. A failed subprocess is logged with
its command, exit code, and both streams via `ProcessOutcome.describe()`
(`dev.locklane.engine.process.ProcessOutcome`). Secrets never appear in a log line.
Every class that catches, spawns a process, or runs on a schedule owns a logger.

The full convention, with the reasoning and the level-by-level guidance, is
`docs/architecture/logging.md`. `LoggingConventionTest`
(`engine/src/test/java/dev/locklane/engine/logging/LoggingConventionTest.java`)
enforces it in CI — a PR that reintroduces a silent swallow fails the build before
review.
