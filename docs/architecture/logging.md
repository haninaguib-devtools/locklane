# Logging

**Status:** binding convention.

The engine's server log (`~/.locklane/locklane.log`) is the only place a failure can be
diagnosed after the fact — the console shows a failed state or a generic HTTP error,
never the cause. This page states the rule that closes that gap: every error or
degraded outcome the engine produces gets logged, with its cause, at the level its
meaning warrants.

## The rule

**Every `catch` block does one of three things:**

1. Logs — the exception passed as the log call's last argument, so the stack trace
   prints (`log.warn("...", e)`, never `log.warn("..." + e)` or `log.warn("...",
   e.getMessage())`).
2. Rethrows, to a caller that logs.
3. Carries a one-line `// silent: <why>` comment naming why silence is correct, and
   logs nothing above `DEBUG`. Anywhere in the body, not necessarily the very first
   line — an `InterruptedException` catch conventionally resets the interrupt flag
   (`Thread.currentThread().interrupt();`) before anything else, including before the
   comment explaining the rest of the body.

A `catch` that does none of these — `catch (X e) { return Optional.empty(); }` with no
comment, `catch (X ignored) {}` — is not allowed, however harmless the failure looks in
the moment. The comment is not decoration: writing "why is this safe to ignore" is what
catches the case where it turns out not to be.

**Pick the level by what the failure means, not by habit:**

- **`ERROR`** — a request or a step failed outright: an import that could not
  complete, an unhandled exception reaching a controller, a task that died on a
  background executor.
- **`WARN`** — a degraded or best-effort outcome: a cache serving stale data because a
  refresh failed, an optional lookup that came back empty because a command failed, a
  best-effort cleanup that left a file behind.
- **`DEBUG`** — expected noise: a WebSocket connection closing while a message was in
  flight, an optional field that failed to parse, anything a `// silent:` comment
  already explains.

**Subprocesses.** A `git`/`gh`/shell command run through `ProcessBuilder` that exits
non-zero is always logged, at `WARN` or `ERROR` depending on whether the caller can
still make progress, with the command name, the exit code, and both streams —
`ProcessOutcome.describe()` (`dev.locklane.engine.process.ProcessOutcome`) is the one
shared way to render those streams into a log line; a class does not write its own
version of it.

**Secrets never appear in a log line.** Tokens (including `GH_TOKEN`), passwords, TOTP
secrets, and encryption keys are never interpolated into a log message, including
inside a subprocess's captured stdout/stderr — a `describe()` call on a command that
was handed a secret via its environment is safe (the secret was an environment
variable, not output), but a command whose *output itself* could echo a secret (rare,
but a bad `gh` invocation could) is logged with that risk in mind.

**Every class that catches, spawns a process, or runs on a schedule owns a logger** —
`private static final Logger log = LoggerFactory.getLogger(<Class>.class);` — even one
whose catches all rethrow, because the day one of them stops rethrowing is the day it
needs one, and adding it then is easy to forget.

**Background work.** A task handed to `projectCloneExecutor`, and a raw `new
Thread(...)`, is wrapped so an uncaught exception is logged at `ERROR` with the task's
identity (project id, session id) before it is lost to the JVM's default handler —
either the task itself catches and logs, or the executor/thread carries an
`UncaughtExceptionHandler` that does. A `@Scheduled` method catches and logs its own
failures at `WARN`/`ERROR` rather than relying on Spring's default scheduling error
handler, which logs with no application context.

**Unhandled controller exceptions.** A single `@ControllerAdvice` catches whatever
reaches it, logs at `ERROR` with the request method and path, and returns a consistent
error body — the backstop for anything a controller's own narrower
`@ExceptionHandler`s don't cover. A local `@ExceptionHandler` still logs, at the level
its own failure warrants, before returning; the advice existing does not excuse a local
handler from logging too.

**The important steps get an INFO trail.** A project import or create logs one INFO
line when it starts (project id, git URL, the `gh` login it acts as or "default") and
one when the project becomes ready (project id, branch) — so a silent failure is
visible even by the absence of the line that should have followed the one that did
log.

## Configuration

`application.yml` carries `logging.level.dev.locklane: INFO`, with a comment noting it
is overridable from `~/.locklane/application-locklane.properties` without needing to
know the key exists — `install.sh` writes that file, and the `locklane` Spring profile
picks it up automatically (`application.yml`'s own comment on `spring.profiles.active`
explains why).

## Enforcement

This rule exists in two forms that change together, in the same PR, the way
`CONSTITUTION.md` §3's protected-path list and `.t-workflow/scripts/protected-paths.sh`
are one rule in two forms:

- **A build-failing test.** `LoggingConventionTest`
  (`engine/src/test/java/dev/locklane/engine/logging/LoggingConventionTest.java`) walks
  `engine/src/main/java` and fails when a `catch` body neither logs, rethrows, nor
  carries a `// silent:` comment; when a class references `ProcessBuilder`,
  `@Scheduled`, or `new Thread(` without declaring a logger; or when a `catch` logs an
  exception without passing it as the log call's last argument. It runs under
  `./mvnw -B test` — check 1 in `AGENTS.md` §Checks, already run on every PR by
  `ci.yml` — so a PR that reintroduces a swallowing catch fails CI before review ever
  starts. The scanner's own classification is unit-tested against small inline Java
  snippets, so the guard itself cannot silently rot.
- **`engine/AGENTS.md`.** A nested instruction file, with `engine/CLAUDE.md` symlinked
  to it the same way the root `AGENTS.md`/`CLAUDE.md` pair works
  (`docs/architecture/local-slots.md` §Alias mechanism), states this rule in a few
  lines and names `LoggingConventionTest`. Every agent session that reads or edits a
  file under `engine/` loads it, so the rule is in view at the moment it matters —
  before the code is written, not only after the build fails.

A change to the convention above changes this document, `LoggingConventionTest`, and
`engine/AGENTS.md` together.
