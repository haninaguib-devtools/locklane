# 781 — Detect installed desktop IDEs and let open-ide launch one locally
Issue: #781 · Part of: #780

## Asked
Today `POST /api/projects/{projectId}/consoles/{id}/open-ide` always starts code-server
for that agent's worktree and returns the engine-proxied URL. Teach the engine which IDEs
are installed on its host and let that endpoint launch a desktop one — VS Code or
IntelliJ IDEA — on the worktree, the way `FileManagerLauncher` launches the OS file
manager for "Folder". Once the client side (the sibling task) lands, "Open IDE" opens the
agent's worktree in the editor chosen in Settings. A desktop launch is honoured only for a
browser on the engine's own machine; any other request is refused, so a remote user can
never pop editor windows on the host's desktop (locklane is multi-user, ADR-105).
code-server stays the default and is unchanged.

## Done when
- A startup probe next to `InstalledAgentsBootstrapper` records which entries of a known
  IDE table are installed, once at boot, like agents: `code-server` (the bundled binary
  `CodeServerService` already resolves under the data dir), `vscode` (`code` on `PATH`,
  `code.cmd` on Windows, or `/Applications/Visual Studio Code.app` on macOS), `intellij`
  (`idea` on `PATH`, `idea64.exe` on Windows, or an `IntelliJ IDEA*.app` under
  `/Applications` or `~/Applications` on macOS). The table is the one place id, label,
  per-OS detection and per-OS launch command are written down. Unit-tested against a
  fake `PATH` and filesystem, per OS.
- `GET /api/ides/installed` returns `{ "installed": [ { "id", "label", "desktop" } ] }`
  in table order, `desktop` false for `code-server` and true for the others. Listed in
  `SecurityConfig` as `authenticated()` like `/api/agents/**`; a test covers the
  anonymous 401.
- `open-ide` accepts an optional JSON body `{ "ide": "<id>" }`. A missing body or
  `"code-server"` behaves exactly as today, same `{ "url": "/api/projects/…/ide/" }`
  response. A desktop id launches the editor on the worktree via the OS (`code <dir>` /
  `idea <dir>` on Linux; the same via `cmd /c` for `.cmd` on Windows; `open -a "<App
  name>" <dir>` on macOS when found under Applications) and responds `200 { "url": null }`.
  An unknown or not-installed id is a 400. The existing owner-only visibility rule (404
  for a console the caller may not see) is unchanged for every id.
- The loopback gate: a desktop id is honoured only when the request's peer address is a
  loopback address and the request carries no `Forwarded` or `X-Forwarded-For` header, so
  a request relayed by an outer reverse proxy on the same machine never counts as local.
  Otherwise 403 and nothing is launched. The check lives in one reusable place (a later
  task applies it to "Folder"). Engine tests cover: loopback peer launches; non-loopback
  peer is 403 with no launch; loopback peer plus `X-Forwarded-For` is 403 with no launch.
- On Linux the launch is detached from the engine's cgroup when `systemd-run` is on
  `PATH` (`systemd-run --user --scope --quiet --collect <cmd>`), otherwise a plain spawn,
  so `locklane stop` does not take a cold-launched editor down with it. **Human-judged on
  a Linux install:** with no IntelliJ instance running, launch it through the endpoint,
  stop locklane, the editor survives.
- Test shape mirrors `FileManagerLauncher`: the launcher takes an injected process
  runner, and the command is a pure function of OS name, IDE id and path, tested per OS
  with no real process spawned.
- `./mvnw -B test` passes.

## Explicitly not
- The client side (settings picker, menu behaviour, stored preference) — the sibling
  client task in initiative #780.
- Applying the same loopback gate to `reveal-in-file-manager` ("Folder") — split to #784.
- Bundling or installing desktop IDEs. Windows detection beyond `PATH` is best-effort.
- Closing the tab still removes the worktree (ADR-104) while a desktop IDE may still have
  it open; accepted, as for code-server, no warning added here.
- JetBrains Gateway (initiative #780's Non-goals).

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The table lives in a new `dev.locklane.engine.ide` package (`KnownIdes`), separate
  from the agent table, with its own store/bootstrapper/controller trio mirroring
  `agent/`'s — the two probes share `InstalledAgentDetector`'s `PATH` scan, which became
  `public` for that (agent, 2026-09-07).
- On macOS an IDE found both on `PATH` and as an app bundle launches through
  `open -a "<App name>"`, the issue's stated form for a bundle found under Applications;
  the `PATH` shim is the fallback only when no bundle is found. Where several
  `IntelliJ IDEA*.app` bundles exist, the lexicographically first is used — best-effort,
  like Windows detection (agent, 2026-09-07).
- The loopback check is `security.LoopbackRequests.isDirectLoopback(HttpServletRequest)`,
  the "one reusable place" #784 will apply to "Folder" (agent, 2026-09-07).
- On the endpoint, an unknown/not-installed/non-desktop id answers 400 before the
  loopback gate answers 403, both after the unchanged 404 visibility check; a launched
  desktop editor is never tracked or stopped by the engine, unlike code-server — that is
  the point of detaching it (agent, 2026-09-07).
- Peer addresses are judged as IP literals only (Java 21 has no
  `InetAddress.ofLiteral`, and `getByName` would resolve a hostname); anything that is
  not a plain IPv4/IPv6 literal is treated as not local (agent, 2026-09-07).

## Deviations / notes
- Driven child of #780: the blocker gate was run by the driver with the sibling
  dispositions file (exit 0), not by this session (ADR-009 D1). Branch cut from
  `wip/780-integration`; every trunk diff here is against `origin/wip/780-integration`.
- The Linux "editor survives `locklane stop`" done-when item is human-judged on a real
  install and not performed here; it remains open for the human.
