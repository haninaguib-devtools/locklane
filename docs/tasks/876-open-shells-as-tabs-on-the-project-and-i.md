# 876 — Open shells as tabs on the project and issue pages
Issue: #876

## Asked
Remove the standalone Shells popup window and open shells as tabs on the pages that own their directory instead: the project agent page (`/projects/:projectId/console`) hosts shells running at the project's main checkout, and the issue page (`/projects/:projectId/issues/:id`) hosts shells running at the issue worktree. Each page's `+` picker offers its agents plus Shell; the issue page's `Agent` button becomes that always-visible `+`.

## Done when
- `grep -rn "locklane-shells" client/src` exits 1 (no singleton popup remains) and the `/shells` routes plus `ShellsWindowComponent`/`ShellsSidenavComponent` are gone.
- On the project agent page, the `+` picker offers the installed agents plus Shell; choosing Shell mints a shell at the main checkout and adds a tab using `cmd="shell"`.
- On the issue page, the `Agent` button is replaced by an always-visible `+` offering Agent (reuses the issue worktree session) plus Shell (mints a fresh shell at the worktree directory).
- Closing an issue's agent session closes its shells with it (their directory is gone).
- `scripts/check.sh` passes locally.

## Explicitly not
- No engine/backend changes; the shell REST endpoints and WebSocket `cmd=shell` pipeline stay as-is.
- No renames of on-the-wire or persisted names (`console` route segment, session id shapes, storage keys) per ADR-112.

## Decisions made along the way
- Shell tabs share the agent tab strip on both pages (`kind: 'shell'`, absent means agent), numbered on their own (`shell`, `shell 2`) after the agent tabs so agent numbering never shifts.
- The `+` picker always offers Shell beside the agent choices; a lone Shell launches directly. The issue page suppresses the Agent choice (`offerAgent`) once its worktree session is live.
- `?session=` handoffs need no `?dir=` for shells: shells persist at mint time, so the page lists a fresh id straight away (unlike never-attached agent sessions, #795).
- Closing an issue agent session fans out shell closes client-side: the engine leaves shell rows behind (a shell owns no worktree), so the Done-when "shells die with the worktree" is client fan-out, no engine change.
- Landing on the console page with shells but no agents shows the shells (no auto-start); the empty-state auto-start only fires with zero tabs. Last-agent close leaves the page only when zero tabs remain.
- Shell tabs never rename (no engine shell rename) and are never written to LastAgentSessionStore/ActiveAgentSessionStore.
- `window-controls-overlay.d.ts` moved from the deleted shells-window dir to `client/src/` (window-chrome.directive.ts still reads it).
- Worktree-list shells section removed outright (each shell now has a home); project-summary "Open shells" navigates to the console page with `?session=`.

## Deviations / notes
- none (all paths were in the issue Scope; `client/src/window-controls-overlay.d.ts` is the relocated declaration the deleted dir housed)

## Checks
- `npx tsc --noEmit -p tsconfig.app.json` — PASS
- `npx tsc --noEmit -p tsconfig.spec.json` — PASS (after spec updates)
- `ng test --watch=false --browsers=ChromeHeadlessCI` — 946 SUCCESS
- `scripts/check.sh` — FAIL (environmental, pre-existing): 4 engine failures in `ProjectCheckoutServiceTest` (3) and `ProjectAgentSessionWebSocketIntegrationTest` (1), all asserting no-token/credential-helper behavior while this environment exports a real `GH_TOKEN`. Zero `engine/` files in this diff; client-only change.
