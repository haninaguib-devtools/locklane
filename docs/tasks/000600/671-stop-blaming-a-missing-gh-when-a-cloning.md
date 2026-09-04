# 671 — Stop blaming a missing gh when a cloning project's workarea does not exist yet
Issue: #671

## Asked
On a fresh install, adding a project and opening it logs `Could not run gh — is it
installed and on PATH?` (from `GhIssueCache.refresh`, via `IssueController.tree`) while
the project is still cloning. gh is installed and on the service's PATH; what is missing
is the project's workarea directory, which `git clone` has not created yet.
`CliGhClient.run` turns every `IOException` from `ProcessBuilder.start()` into the PATH
message, and Java reports a nonexistent working directory with the same
`error=2, No such file or directory` it uses for a missing executable — so the person is
sent hunting for a PATH problem that does not exist. `ProjectGhResources.forProject`
already avoids running gh for a `FAILED` project (#569) but not for a `CLONING` one, and
the client it builds for a `CLONING` project is cached.

Fix both halves: `forProject` serves the same empty, uncached no-checkout context for a
`CLONING` project that it serves for a `FAILED` one, so the first lookup after the
project turns `READY` builds the real client; and `CliGhClient.run` checks that the
working directory exists before `start()` and, when it does not, throws
`GhUnavailableException` naming that directory — the "is it installed and on PATH?"
wording is used only when the directory exists.

## Done when
- A `CLONING` project's `forProject` returns a context whose `issues()`/`pullRequests()`
  are empty and that spawns no gh process; the context is not cached, and a lookup after
  the project is `READY` builds a real client (unit tests in `ProjectGhResourcesTest`).
- `CliGhClient.issues()` against a directory that does not exist throws
  `GhUnavailableException` whose message contains the directory path and not the word
  `PATH` (unit test in `CliGhClientTest`).
- `./mvnw -B test` passes.

## Explicitly not
- The installer's PATH resolution — split to #672.
- The client's behaviour while a project is cloning (it already polls the project until
  READY, #537).

## Decisions made along the way
- The `forProject` guard is written as "anything other than `READY`" rather than
  "`FAILED` or `CLONING`", so a future status that also has no checkout is covered by
  default (Claude, 2026-09-04).

## Deviations / notes
- none
