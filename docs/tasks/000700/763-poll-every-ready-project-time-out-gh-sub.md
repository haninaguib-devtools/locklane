# 763 — Poll every READY project, time out gh subprocesses, size the scheduler pool
Issue: #763 · Part of: #759

## Asked
The engine's 30-second GitHub poll is the only thing that turns a change on GitHub into
an `issuesChanged` notification for the sidenav, so it must keep running for every ready
project and must never be able to stall the rest of the engine. Three defects today:

1. **It polls only the contexts that happen to exist.** `ProjectGhResources.forProject`
   builds and caches a project's context lazily on first request, and `refreshAll`
   iterates that cache. A token renewal (`GhTokenRenewalService`) evicts every project
   of the renewed account, and nothing rebuilds a context until an HTTP request for
   that project arrives — which an idle sidenav never makes, because it waits for the
   event the poll would have produced. Such a project is silently unpolled until
   someone clicks into it or reloads.
2. **`gh` subprocesses have no timeout.** `CliGhClient.run` reads stdout to EOF and
   calls `waitFor()` unbounded (and reads stdout fully before stderr, which can
   deadlock if stderr fills its pipe). A stalled connection to GitHub can hold the poll
   for as long as the kernel takes to give up.
3. **Every scheduled job shares one thread.** No `TaskScheduler` is configured, so
   Spring's default pool of size 1 runs the issue poll, both WebSocket heartbeats, the
   quiescence check, the worktree sweeper (which runs `git fetch`), the token renewal,
   and the release check in series. A slow poll delays every heartbeat; a stall over
   40 s makes the next heartbeat tick close every healthy connection as "no pong
   received", since none of them was pinged.

Fix all three: `refreshAll` iterates every READY project from the repository and builds
a missing context via `forProject` before refreshing it; `CliGhClient.run` (and
`CliReleaseClient`) drain stdout and stderr concurrently and enforce a per-call timeout
(60 s; a timed-out process is destroyed and reported as `GhUnavailableException`); the
scheduler pool is sized so the heartbeats never queue behind a poll
(`spring.task.scheduling.pool.size` in `application.yml`, at least 3, with a comment
naming the jobs that share it). Keep the existing per-project `try/catch` so one
project's failure never skips the others.

## Done when
- A unit test evicts a project's context and asserts the next `refreshAll` still
  refreshes it and broadcasts `issuesChanged` when its issues changed.
- A unit test runs `CliGhClient` against a fake command that never exits and asserts
  it fails with `GhUnavailableException` within the timeout, with no lingering child
  process.
- `application.yml` sets `spring.task.scheduling.pool.size` ≥ 3 with an explanatory
  comment; a test or startup log line confirms the scheduler is not single-threaded
  (for example, two `@Scheduled` methods observed running concurrently in a test slice).
- `./mvnw -B test` passes.

## Explicitly not
- Changing the 30 s poll interval or the `--limit 1000` fetch shape.
- Any WebSocket write serialization (sibling task of #759).

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The bounded subprocess runner is a package-private static helper on `CliGhClient`
  (`runBounded`), shared by `CliReleaseClient` in the same package, rather than a new
  class under `dev.locklane.engine.process`: the issue's Scope names exactly those two
  files and their tests, and a new shared class elsewhere would be scope drift.
  (Claude, 2026-09-07)
- On timeout the runner kills the process's descendants first, then the process
  itself, and waits for it to be reaped before reporting: `gh` runs `git` underneath
  to resolve the repo from the cwd, so a hang could just as well be in a grandchild,
  and killing only the parent would orphan it. (Claude, 2026-09-07)
- The pool is sized 4, not the minimum 3: three of the seven jobs sharing it run
  subprocesses that can be slow (the issue poll, the worktree sweeper's `git fetch`,
  the release check's `gh`), and the two heartbeats plus the quiescence check must
  never queue behind all of them at once. (Claude, 2026-09-07)
- `engine/src/test/resources/application.yml` replaces the main file wholesale for
  tests (its own header says so), so the scheduler test reads
  `src/main/resources/application.yml` explicitly — picking the non-`test-classes`
  copy off the classpath — and boots a minimal `@EnableScheduling` context with that
  value to observe two `@Scheduled` methods running concurrently. The test file is
  `engine/src/test/java/dev/locklane/engine/SchedulingPoolSizeTest.java`: the Done-when
  requires it, and the issue's Scope has no test path for `application.yml` itself.
  (Claude, 2026-09-07)
- `CliGhClient` and `CliReleaseClient` each gain a package-private constructor naming
  the executable and the timeout, so their tests can substitute a fake script for
  `gh` and a sub-second timeout; the public constructors keep the `gh`-on-PATH, 60 s
  behaviour. (Claude, 2026-09-07)
- The existing test `refreshAllNeverBroadcastsForAProjectThatWasNeverLookedUp` is
  renamed to say what it now proves — nothing is broadcast when no project exists —
  because with this change a READY project that was never looked up *is* polled (that
  is the whole point of defect 1). Its assertion is unchanged. (Claude, 2026-09-07)

## Deviations / notes
- A context cached for a project that has since been deleted from the repository is
  no longer refreshed, since the poll now walks the repository rather than the cache.
  It still sits in the cache until an eviction; nothing reads it. Not a behaviour the
  issue asked to change, noted here because it follows from the fix.
- The first full build failed `LoggingConventionTest` (docs/architecture/logging.md)
  on two new catches: the stream drainer's `catch (IOException)`, which stores the
  exception for `text()` to rethrow and now carries a `// silent:` comment saying so,
  and `CliReleaseClient`'s timeout catch, which logged only the message and now passes
  the exception itself. Worth remembering: any new subprocess or catch in the engine
  meets that test.
- Not done here, out of scope: `engine/src/test/resources/application.yml` does not set
  `spring.task.scheduling.pool.size`, so `@SpringBootTest` slices still run their
  scheduled jobs on one thread. Proposed as a follow-up in the PR report.
