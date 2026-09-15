# 921 — Let the IDE proxy admit a project's main-checkout ide-main session
Issue: #921

## Asked
"Open IDE" on a project's main checkout (#831) starts a code-server but the browser then gets a 404 from the engine's IDE proxy. `POST /api/projects/{p}/consoles/{id}/open-ide` admits the `<projectId>-ide-main` session family through `ProjectIdeSessionService.isOpenAndVisibleTo` (that family is deliberately excluded from `IssueWorktreeService.allWorktreeIds`), but `CodeServerProxyAuthorization.upstreamFor`, which both `CodeServerHttpProxy` and `CodeServerWebSocketProxy` consult, checks `allWorktreeIds` only, so every proxied request for that session resolves to no upstream and is refused. Make the proxy apply exactly the visibility rule `openIde` applies — `allWorktreeIds` or `isOpenAndVisibleTo` — so the code-server the engine just started for a main-checkout session is reachable, and keep the owner-only rule (ADR-105) intact for everyone else.

## Done when
- With code-server selected as the IDE, "Open IDE" on a project's main checkout loads the editor in the browser instead of `{"status":404,"path":"/api/projects/<p>/consoles/<p>-ide-main/ide/"}`.
- `CodeServerProxyAuthorizationTest` covers: a `<p>-ide-main` session that is open and owned by the caller resolves to its upstream; the same id for a caller who does not own the project, or when no such session is open, resolves to empty.
- `./mvnw -B -q -pl engine test -Dtest='CodeServerProxy*'` exits 0.

## Explicitly not
- No change to which sessions `allWorktreeIds` lists, to `openIde`, or to the client.
- Does not touch the Java-debugger hang through the proxy or the missing failure logging (separate task).

## Decisions made along the way
- `CodeServerProxyAuthorization` takes `ProjectIdeSessionService` and ORs its `isOpenAndVisibleTo` with the `allWorktreeIds` check — the same two-clause rule `AgentSessionsController.openIde` already applies, so the proxy and the start endpoint can never disagree. `allWorktreeIds` itself is left alone: the `ide-main` family is kept out of it on purpose (#831).

## Deviations / notes
- The test's stub code-server process was `true`, which exits at once; `CodeServerService.start` treats an exited process as a failed launch, so the new case lost that race where the old one happened to win it. The stub is now `sleep 60`. No production code changed for this.
- Found while investigating, not fixed here (own task #922): Java debug start hangs through the proxy, and failed open-ide / shell-open calls log nothing in the engine.

## Agents
- work: claude-code / claude-fable-5-1
