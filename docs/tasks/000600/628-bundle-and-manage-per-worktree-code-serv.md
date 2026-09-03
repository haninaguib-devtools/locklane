# 628 — Bundle and manage per-worktree code-server processes in the engine
Issue: #628 · Part of: #627

## Asked
The engine needs a way to start a code-server (open-source, web-based VS Code)
process for a given console's worktree on demand, reuse it if already running, and
stop it when that console's session ends — all bound to localhost only, with the
working directory resolved server-side (the browser never supplies a path), mirroring
how `FileManagerLauncher` and the shell session flow already resolve a console's
worktree path. This also covers making code-server available on a fresh locklane
install without a manual step, by fetching/bundling it through the existing
install/update scripts.

## Done when
- A new engine endpoint starts a code-server process bound to `127.0.0.1` on a free
  port, with its working directory taken from
  `SessionRegistry.lastKnownWorkingDirectory(consoleId)`, and returns the resulting
  URL.
- A second call for the same console reuses the already-running process rather than
  starting another one.
- The process is stopped when its console/worktree session ends.
- code-server is fetched/installed as part of locklane's own install/update scripts,
  so no separate install step is needed by the person running locklane.
- Process spawning goes through an injectable/testable abstraction (matching
  `FileManagerLauncher`'s `ProcessRunner` pattern), so tests don't spawn a real
  code-server.
- `./mvnw -B test` passes.

## Explicitly not
- The console-tab "Open IDE" button and any client-side wiring — separate task #629,
  blocked on this one.
- Any change to who can reach a console/worktree (existing per-account/per-owner
  access rules apply unchanged; see `CONSTITUTION.md` §4.5).

## Decisions made along the way
- `CodeServerService` (new `dev.locklane.engine.codeserver` package) mirrors
  `FileManagerLauncher`'s shape: an injectable `ProcessRunner`, working directory
  resolved via `SessionRegistry.lastKnownWorkingDirectory`, a nested
  `CodeServerLaunchException` for a failed spawn (agent, 2026-09-03).
- "Stop when the session ends" is wired through a new generic hook,
  `SessionRegistry.addCloseListener(Consumer<String>)`, rather than `SessionRegistry`
  depending on `CodeServerService` directly — the latter would be a circular Spring
  bean dependency, since `CodeServerService` already depends on `SessionRegistry` for
  the working-directory lookup. The listener list generalizes the same role
  `uploadStorage` already plays inline in `SessionRegistry.close()`, and fires on the
  same unconditional terms `uploadStorage.deleteFor` does — not gated on `wasOpen` —
  so a stray running process for an id whose session row is already gone still gets
  stopped (agent, 2026-09-03).
- A free port is allocated by opening and immediately closing a `ServerSocket(0)`,
  then passing that port to code-server's `--bind-addr`; there is a small window where
  another process could take it first. Accepted rather than engineered around:
  code-server has no way to report back an OS-chosen port other than a log line this
  service would have to scrape (agent, 2026-09-03).
- `--auth none` on the spawned code-server process: it is bound to `127.0.0.1` only
  and never reachable off this machine, so locklane's own per-project access rules are
  what actually gate reach here (unchanged by this task, `CONSTITUTION.md` §4.5) — a
  second password layered on top of a loopback-only bind would only add friction
  (agent, 2026-09-03).
- code-server is bundled via its own official installer
  (`curl -fsSL https://code-server.dev/install.sh | sh -s -- --method=standalone
  --prefix=...`) rather than hand-rolling per-OS/arch release-tarball resolution —
  `--method=standalone` never touches a package manager or asks for sudo, matching
  every other dependency `install.sh` already brings in on its own, and installs
  under `$INSTALL_DIR/code-server` so `uninstall.sh`'s existing `rm -rf
  "$INSTALL_DIR"` removes it with everything else (agent, 2026-09-03).

## Deviations / notes
- Fix pass (agent, 2026-09-03), addressing `/t-review`'s findings on PR #638:
  - **Blocker** (the check reuse skill review found no proven pass for
    `./mvnw -B test`, and the failing-locally result is blocker/high "by
    construction"): resolved by getting CI to actually run this check — `ci.yml`
    skips it while a PR is a draft, so this PR is marked ready as part of this fix
    pass, matching the same shape task #619's own PR (#622) went through with the
    identical local-environmental-failure pattern.
  - **High** (`install.sh`/`update.sh` ran the code-server fetch ahead of critical
    steps — account creation in `install.sh`, the server restart in `update.sh` —
    under `set -euo pipefail`, so a `code-server.dev` hiccup would abort an
    otherwise-successful install/update): both scripts now run that step last,
    after the step that actually matters has already succeeded, and soft-fail with
    a warning instead of aborting — matching this file's own existing
    systemd/launchd fallback shape.
  - Medium (`CodeServerService.start()` never checks `process.isAlive()` before
    reusing a cached handle) and low (no code-server version pinned) findings are
    reported but not acted on here — Fix mode addresses only blocker/high; either
    is a reasonable follow-up if the human asks for it by number.
- `ConsolesController`'s constructor gained a third parameter (`CodeServerService`),
  which also touched its existing test file
  (`ConsolesControllerTest`, already in scope as the controller's own test) to update
  every call site and add the new endpoint's tests — not a new file outside the
  declared scope, just the existing test for a file already being changed.
- `SessionRegistry` (`engine/.../pty/**`, inside the declared
  `engine/src/main/java/dev/locklane/engine/**` scope) gained the `addCloseListener`
  hook described above; covered by a new `SessionRegistryCloseListenerTest`.
- `CodeServerService.ProcessRunner` is `public`, unlike `FileManagerLauncher`'s
  package-private twin — `ConsolesController`'s test lives in a different package
  (`persistence`) than `CodeServerService` (`codeserver`) and needs the injectable
  constructor to build a harmless stub.
- `./mvnw -B test`: full local results captured in the PR's `## Checks run` section —
  see there for the exact pass/fail count and which failures are the same
  pre-existing environmental ones already tracked (worktree/persistence tests, a
  machine-local `credential.helper=osxkeychain`), reproduced against an untouched
  `origin/main` before drawing that conclusion.
