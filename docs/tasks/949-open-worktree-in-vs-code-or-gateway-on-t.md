# 949 — Open worktree in VS Code or Gateway on the browser machine over SSH
Issue: #949

## Asked
When the locklane page is served from a remote host (not `localhost`), let the user open an agent's worktree in a desktop IDE running on the *browser* machine, over SSH to the engine host. The Settings dialog's IDE section gains two choices, offered only when the page hostname is not `localhost`: **VS Code (remote SSH)** and **JetBrains Gateway (remote SSH)**. Choosing one changes the tab strip's "Open IDE" item to `Open in VS Code (remote SSH)` / `Open in JetBrains Gateway (remote SSH)`, and clicking it opens a URL-scheme link built entirely on the client, never a request to the engine's open-IDE endpoint:

- VS Code: `vscode://vscode-remote/ssh-remote+<user>@<host>[:<port>]<absolute-worktree-path>`
- Gateway: `jetbrains-gateway://connect#type=ssh&host=<host>&port=<port>&user=<user>&projectPath=<url-encoded-path>` plus either `deploy=true&productCode=<code>&buildNumber=<build>` or `deploy=false&idePath=<remote path>`. Verify the exact parameter names against JetBrains' "connect to a remote server from a link" documentation during work and cite the page in the record.

`host` is `window.location.hostname`. The engine supplies what the client cannot know: the OS user it runs as (`user.name`), each agent session's absolute worktree path (it already exposes the worktree for a tab; extend that payload if the absolute path is not already there), and three optional engine settings for the link: SSH port (default 22), Gateway product code and build number (or a remote IDE path). Expose these through the existing installed-IDEs listing or a small sibling endpoint, whichever needs less new surface.

A browser cannot detect whether VS Code or Gateway is installed on the local machine, so there is no detection. On click the link is opened via `window.location.assign`/`window.open` and, next to the existing "Open IDE failed" note in the tab strip, a dismissable hint appears: *Nothing opened? Install VS Code with the Remote - SSH extension, or JetBrains Gateway, on this computer, and make sure `ssh <user>@<host>` works without a password.* The hint is informational, not an error state.

Existing behaviour is unchanged: code-server everywhere, and local VS Code / local IntelliJ on `localhost`, keep their current store, labels, endpoints and launch paths. The two remote entries are client-side constants, not detector results, and never appear on `localhost`.

## Done when
- Over the network (hostname ≠ `localhost`) the Settings IDE section lists code-server plus the two remote entries; on `localhost` the list is exactly what it is today.
- Picking a remote entry persists like the existing choice, changes the "Open IDE" label accordingly, and clicking it opens the correct link with the engine-supplied user, port and absolute worktree path; no request to the open-IDE endpoint is made for remote entries.
- The install hint shows after a remote click and can be dismissed.
- Specs cover both link builders (host, port default/override, path encoding, Gateway deploy vs idePath variants), the hostname gate, and the label.
- `cd client && npm test` and `cd engine && ./mvnw -q test` pass; `.t-workflow/config` `check` passes.

## Explicitly not
- Detecting whether VS Code or Gateway is installed on the browser machine (browsers do not expose this).
- Setting up SSH keys, known_hosts, or any SSH configuration on either machine.
- Changing code-server, local VS Code or local IntelliJ behaviour, endpoints or labels.
- A per-click product/build picker for Gateway; the values are engine settings.
- Cursor or other VS Code forks (same link shape; can be a follow-up if wanted).

## Decisions made along the way
- Engine facts come from one new sibling endpoint, `GET /api/ides/remote-link?project=<id>&session=<id>` (`RemoteIdeLinkController`, package `ide`), answering `{user, sshPort, path, gateway:{productCode, buildNumber, idePath}}`. Extending the tab payload instead would have meant changing `WorktreeController`/`AgentSessionsController` under `persistence/`, outside the declared scope, and the client does not carry an absolute path for every tab anyway (an issue page's listed sessions arrive with `dir: null`). The endpoint applies the same visibility rule as `open-ide` (`IssueWorktreeService.allWorktreeIds` or `ProjectIdeSessionService.isOpenAndVisibleTo`) and 404s otherwise, so it never leaks a path the caller could not open code-server on; it also serves the project page's minted main-checkout IDE session (#831).
- `user` is `System.getProperty("user.name")`; the three settings are `locklane.remote-ide.ssh-port` (22), `locklane.remote-ide.gateway.product-code`, `.build-number`, `.ide-path` (blank = null on the wire), in `application.yml`.
- The two remote entries are `InstalledIde` constants in `client/src/app/services/remote-ide-link.ts` with a new optional `remote: true` flag; `DefaultIdeStore.available` appends them after the engine's list only away from `localhost`, so `effective` honours a stored remote id there and falls back to code-server on `localhost` with no other change. The Settings dialog needed no code change: its picker already renders `available`.
- VS Code link: port omitted when 22 (VS Code's own default); path segments percent-encoded. Gateway link: `idePath` set → `deploy=false&idePath=…`; else `deploy=true`, plus `productCode`/`buildNumber` only when both are set. Parameter names checked against JetBrains' documented Gateway link (https://www.jetbrains.com/help/idea/remote-development-a.html, "Gateway link: jetbrains-gateway://connect#idePath…"): that page documents `type`, `host`, `port`, `user`, `projectPath`, `deploy=false` and `idePath`, exactly as the issue states. The `deploy=true` + `productCode` + `buildNumber` form is not on that page; it follows the issue's own statement (and the links Gateway's connection UI itself copies).
- The link is opened with `window.location.assign` behind a `protected openLink()` indirection, spied in specs like `currentHostname()` (#497's pattern), since `location.assign` cannot be spied on in Chrome.
- The hint is shown by both the tab strip and the project page's "Open IDE" button (the latter is in scope and uses the same store), as a muted `role="status"` line with its own dismiss, never the red error style.

## Deviations / notes
- `./mvnw -pl engine` alone cannot run: the engine depends on the `client` jar, so engine tests were run with `-am` (and via `scripts/check.sh`).
- `scripts/check.sh` built the client and passed its 1042 specs, then failed in the engine on 16 pre-existing environment-only tests on this machine (BellHookCommandDetachedShapeTest and ProcessTreesTest cannot exec `setsid` / `/bin/true` under the sandbox; ProjectWorktreesServiceTest, WorktreeCleanupSweeperTest, WorktreeCreationServiceTest, SessionRegistryReattachTest, ProjectAgentSessionWebSocketIntegrationTest are the same PTY-timing set #947's record lists). Every `dev.locklane.engine.ide.*` test, including the new `RemoteIdeLinkControllerTest`, passes; the diff touches none of the failing classes' code. CI decides.

## Agents
- work: claude-code / claude-fable-5-1
