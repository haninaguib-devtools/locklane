# 655 — Make Open IDE reachable from a remote browser
Issue: #655

## Asked
Make the "Open IDE" console-tab action work from a browser that reaches locklane over
the network, not only from the same machine. Today the item is hidden unless the page
hostname is exactly `localhost`, and even if it were shown, the engine starts
code-server bound to `127.0.0.1` with no authentication and hands back a
`http://127.0.0.1:<port>/` URL that only a browser on the engine's own machine can
open. That was #627's stated scope, and #629 gated the menu item inside the same
`isLocalHost` block as "Folder" to match it. The two actions do not share a reason:
"Folder" opens the OS file manager, which really does only exist on the local machine
(#497), while code-server is a web IDE whose whole point is being used from another
machine. This task reverses #627's local-only decision. The non-negotiable part is
that reaching the IDE requires the same locklane session, with the same owner-only
project check (`CONSTITUTION.md` §4.5), that reaching the console already requires;
code-server is never exposed on a network interface with `--auth none`, and a separate
code-server password is not an acceptable substitute for locklane's own login.

## Done when
- The "Open IDE" menu item renders on a console tab regardless of
  `window.location.hostname`; "Folder" stays gated on `localhost` exactly as now. The
  console-tabs component spec covers both.
- The URL the `open-ide` endpoint returns is reachable from the requesting browser:
  relative to, or on the same host and port as, the locklane page, never `127.0.0.1`.
- From a browser reaching locklane at a non-`localhost` address, clicking "Open IDE"
  opens a working code-server showing that worktree's files, including the editor's
  WebSocket connection. Human-judged, on a real install.
- An unauthenticated request to the IDE URL is rejected (401/403 or a redirect to
  login, never IDE content), and a request from a logged-in account that does not own
  the console's project gets 404/403. Engine tests cover both.
- code-server itself is not listening on any non-loopback interface: on a running
  install with an IDE open, `ss -ltnp | grep code-server` shows only
  `127.0.0.1:<port>` binds.
- Existing behaviour is unchanged for a `localhost` user: start-or-reuse per console,
  stop when the console's session closes.
- `./mvnw -B test` passes; the client specs pass.

## Explicitly not
- No change to the "Folder" item or its `localhost` gate.
- No TLS/HTTPS work: the IDE is served the same way the rest of locklane is.
- No code-server settings/extension sync, and no sharing of one code-server process
  between users (unchanged from #627's non-goals).
- No change to how code-server is installed or updated (`install.sh`/`update.sh`).

## Decisions made along the way
- **The engine reverse-proxies the IDE; code-server stays on loopback with
  `--auth none`** (agent, 2026-09-03). The IDE is served at
  `/api/projects/{projectId}/consoles/{id}/ide/` on the engine's own port, with HTTP
  and WebSocket traffic forwarded to the console's loopback code-server. That path is
  what makes the URL same-origin with the locklane page (so whatever host the user
  reached locklane at, including an outer TLS reverse proxy, keeps working with no new
  port to open) and what puts every IDE request behind locklane's own session and
  owner-only check. Binding code-server to `0.0.0.0` with its own password was
  rejected: a second password, a random port per console to open through firewalls
  and outer proxies, and a login that is not locklane's.
- **Ownership check reuses `IssueWorktreeService.allWorktreeIds`** exactly as the
  `open-ide` and `reveal-in-file-manager` endpoints do, so the proxy can never answer
  differently from the endpoint that started the process (agent, 2026-09-03). The
  proxy never starts code-server itself: a console whose IDE was never started (or
  whose session has since closed, which stops it) is a 404, same as an invisible one.
- **Both proxies get handler mappings of their own in `CodeServerProxyConfig`, not a
  `@RequestMapping` controller plus the shared WebSocket registry** (agent,
  2026-09-03). Spring MVC's controller mapping runs at order 0, so a controller on the
  path would win the WebSocket handshake and forward it as plain HTTP. And the shared
  registry in `WebSocketConfig` builds its mapping (order 1) with
  `webSocketUpgradeMatch` off, which the integration test caught: it claimed every
  plain request on the path too, answering a page load with its origin check (403
  with an `Origin` header, 404 without) instead of ever reaching the HTTP proxy. The
  IDE WebSocket therefore has its own `WebSocketHandlerMapping` at order 1 with
  upgrade-only matching and the same origin interceptor the registry would have
  added, and the HTTP proxy a `SimpleUrlHandlerMapping` at order 2 behind it.
- **Headers forwarded to code-server**: hop-by-hop headers, `Host`, and every
  `Forwarded`/`X-Forwarded-*` header are dropped, and a request's `Origin` is rewritten
  to code-server's own loopback origin (agent, 2026-09-03). code-server refuses a
  WebSocket whose `Origin` host differs from its `Host` (or `X-Forwarded-Host`); seen
  from code-server the only client is the engine on loopback, so that is the origin it
  is told. Everything else, including `Accept-Encoding`/`Content-Encoding`, passes
  through untouched in both directions, so bodies are never re-encoded.
- **The WebSocket proxy forwards partial frames** (`supportsPartialMessages`) rather
  than raising Tomcat's per-message buffer, so a large editor message (a file save)
  is relayed fragment by fragment instead of tripping the container's 8 KB default
  (agent, 2026-09-03). Upstream is the JDK's own `java.net.http.WebSocket`, behind a
  small connector interface so the relay is unit-tested with a fake and the routing
  is integration-tested with a fake connector bean.
- **The IDE WebSocket uses the same `locklane.security.allowed-origins` list as the
  terminal WebSocket** (agent, 2026-09-03): any origin that may attach a console may
  open its IDE, and no origin that may not attach one gains anything new.
- **`SecurityConfig` gains matchers for `/api/projects/*/consoles/*/ide/**` and
  `/api/projects/*/consoles/*/open-ide`** (agent, 2026-09-03). The second was
  previously unlisted, so an anonymous `POST …/open-ide` fell through to `permitAll`
  and failed with a 500 on a null principal rather than a 401; the proxy's own
  endpoint must not inherit that shape.

## Deviations / notes
- `…/reveal-in-file-manager` has the same unlisted-matcher shape as `open-ide` had
  (anonymous call → 500 instead of 401). Not touched here — it is the "Folder" action's
  endpoint, which this task's non-goals leave alone — reported for its own issue.
- An end-to-end WebSocket check against a real code-server is the issue's human-judged
  done-when; the engine tests cover the relay with a fake upstream and the routing
  through the real server with a fake connector bean.
- `./mvnw -B test`: see the PR's `## Checks run` section for the exact result.
