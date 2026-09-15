# 922 — Fix Java debug start hanging on Activating Extensions through the IDE proxy and log open-ide and shell-open failures
Issue: #922

## Asked
In a code-server opened through the engine's IDE proxy (`/api/projects/{p}/consoles/{id}/ide/`, served by `CodeServerHttpProxy` and `CodeServerWebSocketProxy`), starting a Java debug session — the "Debug" code lens above `main()` in a Maven project with the Java Extension Pack installed — shows "Activating Extensions..." in the status bar indefinitely, with nothing in the Debug Console or Output; "Java: Ready" is shown, so the language server itself is fine. Reloading the window and restarting the engine do not help. The same code-server binary with the same flags and the same user data directory, opened directly at its loopback port, debugs normally — both when started from a shell and when started as a launchd agent with `LimitLoadToSessionType` = `Background` and the engine plist's `PATH` — so the extension, the launchd session and the environment are ruled out and the proxy path is what differs. The remote extension host log (`~/.local/share/code-server/logs/<stamp>/exthost*/remoteexthost.log`) shows two `ProxyResolver#resolveProxy undefined Canceled` errors at every window reload, i.e. requests that were still pending when the host was torn down. Find what the proxy drops or never completes when a debug session starts (candidates: a request path the HTTP proxy does not forward or forwards with a wrong status, a second WebSocket the WebSocket proxy refuses or mishandles, or hop-by-hop or upgrade headers stripped by `NOT_RETURNED`), fix it, and cover the case. Separately, the engine currently logs nothing when `open-ide` or opening a shell fails from the console ("could not open the IDE for that agent — try again", "could not open a shell — try again" in the client), which made this invisible; log those failures with their cause per `docs/architecture/logging.md`.

## Done when
- Through the engine's proxied URL, clicking the "Debug" lens above `main()` of `backend/src/main/java/com/thymeclinic/ThymeApplication.java` in a thyme-clinic worktree (or any Spring Boot main class in a Maven project) starts the program and the Debug toolbar appears; the status bar no longer sticks on "Activating Extensions...".
- A test in `engine/src/test/java/dev/locklane/engine/codeserver/` reproduces the dropped or mishandled request against a stub upstream and passes with the fix.
- A failed `open-ide` and a failed console shell open each produce a `WARN` line in the engine log naming the session id and the cause; `LoggingConventionTest` still passes.
- `./mvnw -B -q -pl engine test -Dtest='CodeServer*,LoggingConventionTest'` exits 0.

## Explicitly not
- No change to which sessions the proxy admits (the `<projectId>-ide-main` 404 is its own task).
- No new code-server flags, extensions, or settings shipped to the user data directory unless the fix proves impossible in the proxy; if so, stop and report before adding any.

## Decisions made along the way
- Root cause: the engine's default `X-Frame-Options: DENY` (Spring Security) reached every proxied code-server response. VS Code Web loads its web-worker extension host — and every webview — in a same-origin iframe; the browser refused that frame, the host never started (`The Web Worker Extension Host did not start in 60s`), and starting a debug session is the first user action that awaits every extension host, so it sat on "Activating Extensions..." forever. Same code-server process, opened directly at its loopback port: debug works.
- Fix: `SecurityConfig` sets `frameOptions` to `sameOrigin()` for every response. The writer sets the header unconditionally at response commit (`XFrameOptionsHeaderWriter` 7.1.1), so the proxy cannot override it per path — a first attempt to set `SAMEORIGIN` inside `CodeServerHttpProxy.relay` was overwritten and dropped. Cross-site framing stays refused.
- `open-ide` and shell-open refusals now log a `WARN` with the session/project and cause (`AgentSessionsController`, `ShellsController`).

## Deviations / notes
- Scope extended, with the human's approval, to `engine/src/main/java/dev/locklane/engine/security/SecurityConfig.java` — pinned by the `## Plan` on the issue.
- Dead ends worth remembering: the WebSocket relay was suspected first (activation events reached the extension host up to 65 s late) but a frame-level trace showed every frame relayed within milliseconds; the delay was the editor's own 60 s retry loop for the worker host. A test engine on `localhost:30001` next to the real one on `localhost:30000` shares the `JSESSIONID` cookie (cookies ignore ports), which produced misleading 401s — use `127.0.0.1` for a second local engine.
- Verified live: with the fix, the Debug lens through the engine proxy launches the program and shows the Debug toolbar.

## Agents
- work: claude-code / claude-fable-5-1
