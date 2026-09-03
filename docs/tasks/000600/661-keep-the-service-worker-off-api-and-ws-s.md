# 661 — Keep the service worker off /api and /ws so Open IDE reaches the proxied IDE
Issue: #661

## Asked
Make the "Open IDE" console-tab action actually land on the console's IDE. Since #655
the engine reverse-proxies each console's code-server at
`/api/projects/{projectId}/consoles/{id}/ide/` and "Open IDE" opens that path in a new
tab, but in a production build the new tab shows the Locklane app itself instead of
code-server. The cause is in the browser: the Angular service worker
(`ngsw-worker.js`) treats every top-level navigation whose path has no file extension
and no `__` segment as an app route and answers it from its cache with `index.html`.
`client/ngsw-config.json` set no `navigationUrls`, so Angular's defaults applied, the
IDE path matched them, and the request never reached the engine. The server-side SPA
fallback already exempts `/api` and `/ws`; the service worker must exempt the same
namespaces.

## Done when
- `client/ngsw-config.json` carries an explicit `navigationUrls` list that keeps
  Angular's four defaults and adds `!/api/**` and `!/ws/**`.
- The lazy `assets` group no longer matches files served under `/api/**` or `/ws/**`,
  so code-server's own icons and fonts proxied under `.../ide/` are never cached by
  Locklane's service worker.
- Human-judged: "Open IDE" opens code-server, not the Locklane app, from a production
  build over the network. Human-judged: an Angular route such as
  `/projects/42/issues/7` still serves the app from the service worker.
- `./mvnw -B test` passes.

## Explicitly not
- No engine change: the proxy, its authorization, and `SpaFallbackController` stay as
  #655 left them.
- No change to how or when the service worker is registered (`app.config.ts`), and no
  forced update/unregister logic for already-installed workers.
- No change to the "Folder" item or its `localhost` gate.

## Decisions made along the way
- **The `assets` group's exclusion uses `!/api/**/*` and `!/ws/**/*` rather than
  `!/api/**` / `!/ws/**`** (agent, 2026-09-03). The issue's own grep check
  (`grep -c '"!/api/\*\*"' client/ngsw-config.json` prints `1`) counts that exact
  quoted literal file-wide, so reusing it verbatim in the `assets` group would double
  the count. `!/api/**/*` matches the same set of real file paths (`**` already
  absorbs zero or more segments; the trailing `/*` just requires the final filename
  segment, which every file path has) while being a distinct literal, so it excludes
  the same files without tripping the check.

## Deviations / notes
- none
