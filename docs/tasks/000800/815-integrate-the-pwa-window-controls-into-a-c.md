# 815 — Integrate the PWA window controls into a compact desktop-style layout
Issue: #815

## Asked
Make LockLane feel like a cohesive desktop app: integrate the native window controls into the app surface, keep the existing full-width header (branding, project name, running-agent indicator, Add project, account menu) tinted by the selected project's accent, paint the browser's title strip in the sidebar's off-white, and quiet the sidebar. Keep the Angular PWA, existing terminal behavior, and every existing action. (Revised 2026-09-07 — see ## Feedback; the first direction, a sidebar-owned brand/footer and one dark compact header per view, was withdrawn by the maintainer.)

## Done when
- Installed desktop Chromium with visible Window Controls Overlay uses the available title-bar area: the header extends up into it, the title strip is painted in the sidebar's off-white, empty header space drags. Native buttons and browser-required menu/security controls remain usable; browser-owned UI is not claimed removable.
- Reuse #741's manifest opt-in and visibility/geometrychange support, accounting for all four titlebar-area geometry values and controls on either side. Empty header space drags; interactive controls never drag.
- The browser-owned title strip uses the sidebar's off-white, never black or orange. The full-width header keeps its existing composition (brand left, project name centered, running-agent indicator / Add project / account menu right) with existing focus/permission rules. The sidebar carries no branding and no footer actions.
- Agent view, issue workspaces, summaries and overview keep their existing headers and tab strips; no extra per-view header. Focused windows stay minimal.
- Warm off-white sidebar; header tinted by the project accent exactly as before (existing light blend), plain with no project; manifest/HTML `theme_color` is the sidebar off-white. Terminal renderer/theme is unchanged.
- Quiet tree selection/separators retain filtering, hierarchy, expansion, selection, all project/issue actions, resizing, navigation, keyboard and touch usability. Branding still navigates to overview.
- Preserve all agent/shell operations, running-agent navigation, account/settings/GitHub/admin/about/sign-out actions, loading/error states and release/update banners. No unnecessary terminal remount, input loss, interrupted connection or stale dimensions after resize.
- Normal tabs, unsupported browsers and overlay-disabled windows remain compact without overlay-only gaps. Narrow windows, long names, many tabs, focus and touch remain usable without native-control collisions.
- Maintainer verifies the delivered build in a real installed macOS Chrome/Edge PWA, with exact build/browser and appearance/interaction evidence for overlay on/off and normal-tab fallback. Record observed manifest update/relaunch steps; do not prescribe reinstall without evidence.

## Explicitly not
No native wrapper, packaging, installer/server/API/persistence changes, terminal renderer redesign, fake controls, new project-switcher/split/status features, permission changes, or unrelated form/content redesign. Browser-required security/app controls remain browser-owned.

## Origin
none

## Verification
- role: maintainer — required: true
  what: Exercise this build in a real installed macOS Chrome/Edge PWA; capture desktop appearance and verify window dragging, native buttons, app/browser menus, back navigation, agent tabs and resizing. Check overlay on/off and a normal browser tab. Record exact build/browser, evidence, and any observed manifest update/relaunch steps.
  state: pending
  evidence: awaiting — revision: `none yet`
  by: maintainer — date: —

## Feedback
- 2026-09-07, maintainer, in-session after opening the first delivered build as an installed PWA (screenshot supplied; mockup of the response: https://claude.ai/code/artifact/a3294de0-5ccd-442f-ae5d-51913a739a6d). Three items, in the maintainer's words: (1) "The top bar is black it should be same color as the sidenav"; (2) the header "should go back to how it was before, with the agents widget, add project and user menus. remove the 'add project' and user menus from the sidenav"; (3) "The header should change colors as before dependent on the project settings selected by the user." Asked separately whether to keep the new dark tab-bar header on the agent view: "keep the old tab strip".
  classification: proposed scope change — reverses three Done-when lines and the design reference; authorized by the maintainer ("go") and the issue body was rewritten to match before any file changed (Goal, Done-when lines 1, 3, 4, 5, Design reference).
  response: implemented in this pass — `theme_color`/`theme-color` `#f2f0eb`; original full-width `.topbar` restored (brand, centered project name, indicator, Add project, account menu; `deriveProjectBackgroundTint` and its spec restored); sidebar brand/footer removed; main-content, project-console, console-tabs and console-indicator reverted to their pre-task templates/styles; with the overlay active the topbar extends into the title-bar area (`--titlebar-bottom` inset, strip painted `--sidebar`, empty space drags). Kept: the shared `appWindowChrome` directive (main shell and Shells window), the quieter sidenav tree styling, geometry variables for all four `titlebar-area-*` values.

## Decisions made along the way
- 2026-09-07, implementation: declared scope is not protected; no plan stage required. The issue's explicit required maintainer verification is carried into this record rather than lost because there is no plan.
- 2026-09-07, implementation: extend #741's actual-visibility/geometrychange mechanism into one shared directive; CSS consumes all four native title-bar geometry values. Keep the existing manifest opt-in. No installation-only or platform-position heuristics.
- 2026-09-07, implementation: retain component-owned live terminal bodies and tracked identities; restructure presentation around them. Sidebar owns branding and existing Add project/account actions; project and issue workspaces own their single compact header.
- 2026-09-07, implementation: `app-console-indicator` now mounts once per view's own compact header (project summary, issue main-content, project console, overview) instead of once globally, matching each view "own[ing] its single compact header." `client/src/app/app.component.spec.ts` updated to flush the indicator's own consoles/issues/console-sessions fetches at each navigation that mounts a fresh header instance, since it now re-fetches on every such mount rather than once for the app's lifetime.

## Deviations / notes
- Started from fetched `origin/main` at `7d9f86f` in the provided detached task checkout; the separate primary checkout's behind-only local `main` was left untouched.
- Required installed-PWA verification remains pending; ordinary browser or emulated geometry checks cannot prove native integration.
- `./mvnw -B test` initially failed: 5 `AppComponent` spec failures (`app.component.spec.ts`) left over from the console-indicator's changed mount lifecycle above, plus an unrelated Karma/webpack chunk-load timeout that only appeared when running that single spec file in isolation (`--include`) and never in the full suite. Fixed the 5 real failures; the isolation-only flake was confirmed absent from the full `ng test --watch=false --browsers=ChromeHeadlessCI` run (860/860 passing) and needed no code change.
- 2026-09-07 feedback pass: `[style.background-color]` replaces the old `[style.background]` shorthand on `.topbar` so the overlay-active `background-image` gradient (the sidebar-colored title strip) is not reset by the inline tint; `app.component.spec.ts` asserts `style.backgroundColor` accordingly. The earlier note about the console indicator's per-view mount no longer applies — it is a single global instance again.
- `ng build`'s production build reports `sidenav.component.css exceeded maximum budget` (7.59 kB against a 4 kB warning threshold, well under the 8 kB error threshold that would fail the build) — a warning, not a failure; the component's CSS grew with the quieter tree/selection styling this task asked for. Left as-is; flagged for the human in case it's worth a follow-up.
