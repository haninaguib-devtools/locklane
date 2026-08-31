# 466 — Link the release banner to the release's notes
Issue: #466 · Part of: #462

## Asked
The "A newer version (X) is available." banner becomes actionable: instead of a bare
version number, it links to that release's notes on GitHub
(`https://github.com/<owner>/<repo>/releases/tag/v<version>`), opening in a new tab.
The engine already knows the repository (`locklane.release-check.repository` in
`application.yml`) and the tag; carry the release URL in the `releaseAvailable` event
payload (or derive it client-side from a repository the engine exposes — implementer's
choice) and render the banner text as a link.

## Done when
- With a newer permanent release available, the banner renders a link whose `href` is
  that release's GitHub Releases page.
- The link opens in a new tab and is covered by a client test asserting the href for a
  given `releaseAvailable` event.
- Late joiners get the same link: a client connecting after the release was detected
  (the `EventsWebSocketHandler` replay path) sees the identical banner.

## Explicitly not
- No update-from-the-banner action (no button that runs the update) — the banner
  informs and links, nothing more.
- The separate client-bundle `update-banner` (service-worker reload) is untouched.

## Decisions made along the way
- Of the issue's two offered routes, the URL rides in the `releaseAvailable` payload
  (engine-side), not derived client-side from an exposed repository: the engine's
  stored newer-release state already feeds both the broadcast and the on-connect
  replay in `EventsWebSocketHandler`, so one source guarantees late joiners the
  identical link with no second payload to keep in sync (agent, 2026-08-31).
- The URL comes from GitHub itself — `gh release view --json tagName,url` — rather
  than being string-assembled from the configured repository and tag: `url` is the
  release's actual Releases-page address (`.../releases/tag/v<version>`), so no
  assumption about tag-to-URL mapping is baked into engine code (agent, 2026-08-31).
- `GhRelease` gains a `url` component and `ReleaseUpdateChecker` stores/exposes a
  `NewerRelease(version, url)` record; `EventsWebSocketHandler`'s supplier carries it
  so greeting-replay and broadcast build the same payload shape (agent, 2026-08-31).
- Client-side the `url` field is optional in the `ReleaseAvailableEvent` guard: a
  client rolled out ahead of its engine still accepts the old version-only payload and
  falls back to today's plain-text banner (agent, 2026-08-31).
- The whole banner sentence is the link (issue: "render the banner text as a link"),
  `target="_blank"` with `rel="noopener noreferrer"` (agent, 2026-08-31).

## Deviations / notes
- The issue's Scope names the engine's `src/main` packages (`github/`, `ws/`) and the
  client sources; their test twins (`engine/src/test/.../github/`, `.../ws/`, the
  `.spec.ts` files beside the named client files) are touched too — the payload-shape
  tests pin the very behavior the issue changes, and the done-when itself demands a
  client test. Treated as part of the named scope, not a widening.
