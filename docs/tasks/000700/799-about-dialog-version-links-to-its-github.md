# 799 — About dialog version links to its GitHub release page
Issue: #799

## Asked
In the About dialog, the line that reads "version 0.2.20" today should be a link that
opens that version's GitHub release page in a new tab, so a person can get from the
version they are running to its release notes in one click. The engine already knows
the repository its releases live in (`locklane.release-check.repository`) and already
hands the client a ready-made URL for the newer-available release banner (#466); this
task does the same for the running version. A development build (a `-SNAPSHOT`
version) has no release page and stays plain text, as does an engine too old to send
the link.

## Done when
- The `engineVersion` greeting (`EventsWebSocketHandler#afterConnectionEstablished`)
  carries a `releaseUrl` field of the form
  `https://github.com/<locklane.release-check.repository>/releases/tag/v<release>`
  whenever `release` is a non-snapshot version; omitted when `release` ends in
  `-SNAPSHOT`. Covered by `EventsWebSocketHandlerTest` for both cases.
- `EngineVersionEvent` in `client/src/app/services/events.service.ts` accepts an
  optional `releaseUrl: string`; `isEngineVersionEvent` still accepts the old payload
  without it.
- `RunningVersionService` exposes the URL alongside the version (`null` when absent),
  covered by its spec.
- In `about-dialog.component.html`, when a URL is present the version text is an `<a>`
  with that `href`, `target="_blank"` and `rel="noopener"` (the app's existing
  external-link convention); when absent, the existing plain "version X" / "version
  unknown" text is rendered unchanged. Covered by `about-dialog.component.spec.ts` for
  both branches.
- `./mvnw -B test` passes (engine and client suites).
- Human check: on a released build, clicking the version in About opens
  `https://github.com/haninaguib-devtools/locklane/releases/tag/v<version>` in a new
  tab; on a dev build the version is plain text.

## Explicitly not
- Does not change the newer-release banner or its URL (#466).
- Does not add a link for snapshot builds (no page exists to point at).
- Does not move the repository name into the client; the engine stays the single
  owner of `locklane.release-check.repository`.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Rebasing onto `origin/main` conflicted with #790 (`EventsWebSocketHandler`'s own
  concurrent constructor change, adding a `waitingSessions` supplier). Resolved by
  adding distinct overloads for `repository`-only and `waitingSessions`-only test
  callers, both delegating to one canonical constructor carrying every parameter, so
  neither feature's existing call sites needed editing beyond the two that named the
  full constructor directly (self, no other reviewer at rebase time — 2026-09-07).

## Deviations / notes
- none
