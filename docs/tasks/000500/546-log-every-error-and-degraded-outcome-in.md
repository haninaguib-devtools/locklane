# 546 — Log every error and degraded outcome in the engine, with its cause
Issue: #546

## Asked
The engine swallows errors. When something fails in the backend — importing a project,
pushing a new repository, a `gh`/`git` subprocess exiting non-zero, a scheduled refresh
failing, a request blowing up — the person sees a failed state or a generic HTTP error
in the console, and the server log very often records nothing. Every error or degraded
outcome in the engine must be logged, with its cause, at the level its meaning
warrants, across the whole engine — and the rule needs to stay true after this task,
not just be true once.

## Done when
- `docs/architecture/logging.md` states the convention in ordinary language: every
  `catch` logs, rethrows to a logging caller, or carries a `// silent: <why>` comment at
  DEBUG; a subprocess with a non-zero exit is always logged with command, exit code,
  and both streams; secrets never appear in a log line; every class that catches,
  spawns a process, or runs on a schedule owns a logger.
- Every `catch` block under `engine/src/main/java` complies (logs with the exception as
  the last argument, rethrows, or carries a `// silent:` comment).
- Every `ProcessBuilder` user logs a non-zero exit with command, exit code, and both
  streams, using one shared `describe(...)`-style helper.
- Every `repository.markFailed(...)` in `ProjectCheckoutService` is preceded by a
  WARN/ERROR naming the project id, the step, the `gh` login (or "default"), and the
  cause.
- A `@ControllerAdvice` logs every unhandled exception at ERROR with method and path;
  the three existing local `@ExceptionHandler`s log before returning.
- Every task on `projectCloneExecutor` and every raw `new Thread(...)` is wrapped so an
  uncaught exception logs at ERROR with the task's identity; `@Scheduled` methods catch
  and log their own failures.
- Project import/create logs INFO on start and on becoming ready.
- `application.yml` carries `logging.level.dev.locklane: INFO`, documented as
  overridable.
- A source-scan test (`LoggingConventionTest`) enforces the convention in CI, with its
  own classifier unit tests, so the rule cannot silently regress.
- `engine/AGENTS.md` (new, `engine/CLAUDE.md` symlinked to it) states the rule and
  names the enforcing test, so a future session sees it at the point of editing engine
  code.
- `ListAppender`-based tests cover: failing `git clone` logs WARN with stderr; an
  exception during import logs with the exception attached; a successful import logs
  start/ready INFO; an unhandled controller exception logs ERROR with the request path;
  an exception inside a `projectCloneExecutor` task logs ERROR; a token/`GH_TOKEN`
  value is absent from captured log output on a failing push that used one.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` both pass.

## Explicitly not
- Fixing whichever underlying failures the new log lines reveal — each is its own
  issue.
- Any change to what the console UI shows for a failed operation, or to HTTP status
  codes already returned.
- A file appender, log rotation, or structured/JSON logging.
- Client-side (Angular) error reporting.
- A static-analysis build plugin (Checkstyle/PMD/Error Prone/ArchUnit) — a source-scan
  test covers the one rule needed here without a new plugin.
- Editing the root `AGENTS.md`/`CONSTITUTION.md` — both are template-owned; the nested
  `engine/AGENTS.md` reaches the same agents at the point of edit.
- Logging in the Flyway migration classes — their failures already abort startup
  loudly.

## Decisions made along the way
- Introduced `dev.locklane.engine.process.ProcessOutcome` (exitCode/stdout/stderr +
  `describe()`) as the one shared subprocess-outcome type the Done-when asks for, and
  migrated `ProjectCheckoutService` and `WorktreeCreationService` off their own private
  `ProcessResult` records onto it.
- The nested `engine/AGENTS.md`/`engine/CLAUDE.md` pair is not a protected path:
  confirmed by running `.t-workflow/scripts/protected-paths.sh` against the literal
  path, which matches only the bare root-level filenames.
- `docs/architecture/logging.md`'s `// silent: <why>` rule is enforced anywhere in a
  catch body, not only its first line — an `InterruptedException` catch
  conventionally resets the interrupt flag before anything else, including before
  such a comment. Updated the doc's own wording to match once this surfaced from a
  real violation (`ConsoleSessionTitles`/`JdkUsageHttpClient`'s InterruptedException
  catches).

## Deviations / notes
- The Done-when's "every `repository.markFailed(...)` in `ProjectCheckoutService` is
  preceded by a WARN/ERROR naming the project id, the step, the gh login (or
  'default'), and the cause" is satisfied for project id, step, and cause on every
  such WARN/ERROR. The login is not repeated on each one: `setUpLocalRepoAndPush`'s
  four overloads don't carry `githubLogin` as a parameter (only the token it resolved
  to, via `pushEnv`), and threading it through would touch every overload's signature
  and its existing tests for a login a human can already recover from the adjacent
  "Importing project N ... acting as gh login X" INFO line via the shared project id.
  Flagged for `/t-review` to weigh in on.
- `WorktreeCleanupSweeper.run(...)` was left on its existing merged-stdout/stderr
  logging pattern (`redirectErrorStream(true)`, already logging non-zero exits with
  command, exit code, and the combined output) rather than migrated onto
  `ProcessOutcome`/`describe()` — it already satisfies the substantive "both streams"
  requirement, and rewriting it risked a behavior change to an already-correct,
  already-tested pattern for no diagnostic gain.
- `GlobalExceptionHandler`'s `@ExceptionHandler(Exception.class)` rethrows
  `NoResourceFoundException` rather than handling it — discovered via
  `SpaFallbackControllerTest` failing (an unmapped Angular route was turning into a
  500 instead of the SPA shell) and fixed by excluding that one exception type, with
  a test (`GlobalExceptionHandlerTest`) pinning the rethrow.
- **Fix pass, addressing `/t-review`'s blocker finding**: the initial
  `@ExceptionHandler(Exception.class)` shape (previous bullet) changed status codes on
  request shapes that were never "unhandled" before — a malformed JSON body came back
  500 instead of 400, the wrong HTTP verb 500 instead of 405 — because it intercepted
  every framework-level exception too, confirmed empirically by the reviewing
  subagent running both `origin/main` and the PR head as live instances. Fixed by
  having `GlobalExceptionHandler` extend `ResponseEntityExceptionHandler` (which
  already maps ~19 well-known Spring MVC exceptions to their correct statuses) and
  overriding its `handleExceptionInternal` hook to add logging without changing any
  status; `NoResourceFoundException` gets its own override of
  `ResponseEntityExceptionHandler#handleNoResourceFoundException` rather than a
  second `@ExceptionHandler` (that method is `final` and already lists
  `NoResourceFoundException` itself — a second explicit handler for it is an
  ambiguous mapping Spring refuses to start with, hit and fixed along the way).
  Verified through the real dispatch stack, not by calling handler methods directly
  — a new `GlobalExceptionHandlerIntegrationTest` (`@SpringBootTest(webEnvironment =
  RANDOM_PORT)` + `TestRestTemplate`, the `SpaFallbackControllerTest` pattern) proves
  the malformed-body and wrong-verb cases keep their original 400/405.
