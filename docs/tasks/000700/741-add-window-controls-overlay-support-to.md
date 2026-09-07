# 741 — Add Window Controls Overlay support to shells-window
Issue: #741

## Asked
The shells-window route (the focused, single-project shell view used when a project is
opened as its own installed PWA window) is already chromeless — it has no in-app topbar
or sidebar. When installed as a PWA, this route currently still shows the OS's own
window title bar. Enable the Window Controls Overlay (WCO) so the OS window controls
(close/minimize/maximize) are overlaid directly onto the shells-window UI instead,
giving it a fully custom, borderless title area — similar to how VS Code's own window
looks when installed.

## Done when
- `client/public/manifest.webmanifest` declares
  `"display_override": ["window-controls-overlay", "standalone"]`.
- The shells-window component reserves space for the overlay's system controls using
  `env(titlebar-area-*)` CSS env variables (with sensible fallbacks for browsers that
  don't support WCO), and marks its own draggable chrome region with
  `-webkit-app-region: drag` / interactive children with `-webkit-app-region: no-drag`.
- The layout change is gated on `navigator.windowControlsOverlay` (feature-detected,
  e.g. via its `geometrychange` event), so behavior on non-Chromium browsers (Safari,
  Firefox) and in a normal browser tab (not installed) is unchanged.
- Manually verified: installing the app as a PWA in a Chromium browser and opening a
  project's shells window shows the OS window controls overlaid on the shells-window UI
  with no dead/unclickable area and no overlap with interactive elements.

## Explicitly not
- The main app-level `.topbar` (used everywhere except the shells-window route) is out
  of scope for this task — it stays as-is.

## Decisions made along the way
- none

## Deviations / notes
- none
