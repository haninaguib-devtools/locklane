# 971 — Default the Gateway backend to IntelliJ IDEA Ultimate 262.9437.185
Issue: #971

## Asked
Give the engine a default JetBrains Gateway backend so a remote Gateway open works out of the box. Today `locklane.remote-ide.gateway.product-code` and `build-number` in `engine/src/main/resources/application.yml` are blank, so `GET /api/ides/remote-link` reports both empty and the client shows "JetBrains Gateway needs locklane.remote-ide.gateway.ide-path, or product-code together with build-number, set on the engine" instead of opening a link (#949, #957). Set the defaults to `product-code: IU` and `build-number: 262.9437.185` (IntelliJ IDEA Ultimate; Gateway installs that backend once under `~/.cache/JetBrains/RemoteDev/dist/` on the engine host and reuses it for every worktree). The yml comment states where the values come from and that `LOCKLANE_REMOTE_IDE_GATEWAY_*` env vars or `~/.locklane/application-locklane.properties` override them, `ide-path` taking precedence when set.

## Done when
- `grep -n 'product-code: IU' engine/src/main/resources/application.yml` and `grep -n 'build-number: 262.9437.185' engine/src/main/resources/application.yml` both match.
- With no override, `GET /api/ides/remote-link` returns `gateway.productCode` = `IU` and `gateway.buildNumber` = `262.9437.185`, so the client's `gatewayLinkIsUsable` is true and a Gateway click opens a `jetbrains-gateway://connect#...deploy=true&productCode=IU&buildNumber=262.9437.185` link.
- `RemoteIdeLinkControllerTest` covers the new defaults (any test asserting blank defaults is updated); the project `check` command passes.

## Explicitly not
- No change to `RemoteIdeLinkController` logic, the client link builder, or the `ide-path` option.
- No auto-detection of an installed IDE on the engine host.

## Decisions made along the way
- `engine/src/test/resources/application.yml` shadows the shipped one on the test classpath, so the `@WebMvcTest` still sees blank gateway keys. The existing case keeps asserting blanks (now explained in a comment) and a new `shippedDefaultsNameAGatewayBackend` test reads `src/main/resources/application.yml` from disk and asserts the defaults.
- The build number comes from `~/Applications/IntelliJ IDEA.app/Contents/Resources/product-info.json` on the maintainer's machine (2026-09-16); the yml comment says so and how to bump it.

## Deviations / notes
- `scripts/check.sh` (`./mvnw -B test`) fails locally on this Mac with the same pre-existing environmental set as `origin/main` (no `setsid`/`/bin/true`, git-worktree and keychain-dependent persistence tests, PTY reattach, WebSocket integration): BellHookCommandDetachedShapeTest, ProjectWorktreesServiceTest, ProjectCheckoutServiceTest, WorktreeCleanupSweeperTest, WorktreeCreationServiceTest, SessionRegistryReattachTest, ProcessTreesTest, ProjectAgentSessionWebSocketIntegrationTest. `RemoteIdeLinkControllerTest` passes (7/7); CI is the authoritative run.

## Agents
- work: claude-code / claude-fable-5-1
