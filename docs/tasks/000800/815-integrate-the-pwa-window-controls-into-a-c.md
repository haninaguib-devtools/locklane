# 815 — Integrate the PWA window controls into a compact desktop-style layout
Issue: #815

## Asked
Make LockLane feel like a cohesive desktop app: replace the broad orange title bar and stacked navigation/tab rows with a full-height warm neutral sidebar and one compact dark workspace header. Keep the Angular PWA, existing terminal behavior, and every existing action.

## Done when
- Installed desktop Chromium with visible Window Controls Overlay uses the available title-bar area without a separate orange strip or empty spacer. Native buttons and browser-required menu/security controls remain usable; browser-owned UI is not claimed removable.
- Reuse #741's manifest opt-in and visibility/geometrychange support, accounting for all four titlebar-area geometry values and controls on either side. Empty header space drags; interactive controls never drag.
- Desktop sidebar reaches the top, sharing its neutral surface with native traffic lights and compact branding. Add project and account actions move to its footer with existing focus/permission rules preserved.
- Project agents have one compact header containing back-to-issues, project name, tabs/actions and running-agent access. Issues, summaries and overview use the same shell without irrelevant controls or duplicate tabs. Focused windows stay minimal.
- Warm off-white sidebar, dark neutral terminal-workspace header, neutral manifest/HTML browser colors, and restrained project accent indicators replace broad colored header bands. Terminal renderer/theme is unchanged.
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
none

## Decisions made along the way
- 2026-09-07, implementation: declared scope is not protected; no plan stage required. The issue's explicit required maintainer verification is carried into this record rather than lost because there is no plan.
- 2026-09-07, implementation: extend #741's actual-visibility/geometrychange mechanism into one shared directive; CSS consumes all four native title-bar geometry values. Keep the existing manifest opt-in. No installation-only or platform-position heuristics.
- 2026-09-07, implementation: retain component-owned live terminal bodies and tracked identities; restructure presentation around them. Sidebar owns branding and existing Add project/account actions; project and issue workspaces own their single compact header.
- 2026-09-07, implementation: `app-console-indicator` now mounts once per view's own compact header (project summary, issue main-content, project console, overview) instead of once globally, matching each view "own[ing] its single compact header." `client/src/app/app.component.spec.ts` updated to flush the indicator's own consoles/issues/console-sessions fetches at each navigation that mounts a fresh header instance, since it now re-fetches on every such mount rather than once for the app's lifetime.

## Deviations / notes
- Started from fetched `origin/main` at `7d9f86f` in the provided detached task checkout; the separate primary checkout's behind-only local `main` was left untouched.
- Required installed-PWA verification remains pending; ordinary browser or emulated geometry checks cannot prove native integration.
- `./mvnw -B test` initially failed: 5 `AppComponent` spec failures (`app.component.spec.ts`) left over from the console-indicator's changed mount lifecycle above, plus an unrelated Karma/webpack chunk-load timeout that only appeared when running that single spec file in isolation (`--include`) and never in the full suite. Fixed the 5 real failures; the isolation-only flake was confirmed absent from the full `ng test --watch=false --browsers=ChromeHeadlessCI` run (860/860 passing) and needed no code change.
- `ng build`'s production build reports `sidenav.component.css exceeded maximum budget` (7.59 kB against a 4 kB warning threshold, well under the 8 kB error threshold that would fail the build) — a warning, not a failure; the component's CSS grew with the quieter tree/selection styling this task asked for. Left as-is; flagged for the human in case it's worth a follow-up.
