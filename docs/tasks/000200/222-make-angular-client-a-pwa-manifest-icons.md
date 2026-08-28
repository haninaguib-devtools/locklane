# 222 — Make Angular client a PWA (manifest, icons, service worker)
Issue: #222

## Asked
Make LockLane's Angular client (`client/`) installable as a Progressive Web App — so it
can be added to the home screen on iOS Safari (and other browsers) and look/behave like a
native app. This fulfills the stack already ratified in `CONSTITUTION.md` §4.1 / ADR-002
("Angular PWA client"), which has not yet been implemented: today there is no web
manifest, no service worker, and no app icon set beyond a bare `favicon.ico`.

## Done when
- `client/` has a web app manifest (`manifest.webmanifest` or equivalent) declaring app
  name, icons, `display: standalone`, and theme/background colors, linked from
  `index.html`
- An app icon set exists (at minimum a maskable/any-purpose 192px and 512px PNG, plus an
  Apple touch icon) under `client/public/`, replacing the bare-`favicon.ico`-only state
- `index.html`'s `<title>` reflects the app name (currently the generic placeholder
  "Client")
- A service worker is registered via `@angular/service-worker`
  (`ng add @angular/service-worker`), with `ngsw-config.json` caching the built app
  shell/assets, wired into the production build
- `ng build --configuration production` succeeds and produces the manifest +
  `ngsw-worker.js` in `dist/client/browser`, which lands on the `engine` module's
  classpath the same way the rest of the static assets already do
- Loading the app in a browser shows a valid, installable manifest (e.g. passes Chrome
  DevTools' "Installability" check) and a correct Apple touch icon

## Explicitly not
- Provisioning HTTPS/TLS for non-localhost deployment — iOS Safari requires HTTPS (or
  `localhost`) to install a PWA, and this repo currently has no TLS/reverse-proxy setup.
  Deferred to its own issue.
- Advanced offline data sync or custom caching strategies beyond the default app-shell
  asset caching `@angular/service-worker` provides out of the box.

## Decisions made along the way
- none

## Deviations / notes
- none
