# 860 — Push the agent-is-waiting notification when the app is closed, via Web Push
Issue: #860 · Split from: #853

## Asked
Deliver the "agent is waiting" notification when the Locklane app is closed, on the
phone or the desktop, through Web Push. The engine holds one VAPID key pair,
generated once under its data directory, and stores push subscriptions per account
(a migration, per-owner under ADR-105); the client subscribes through the service
worker when the user opts in; on a session becoming waiting with reason `bell`, the
engine pushes to the subscriptions of that project's owner, and the service worker
shows the notification and opens the agent on click. Needs an ADR: it is the first
time the engine calls an external service on the user's behalf and the first
per-user secret it holds.

## Done when
*(pinned by `/t-plan` on 2026-09-10; the issue deferred it to the plan)*
- The engine generates one VAPID key pair on first start under `locklane.data-dir`,
  owner-only like the encryption key file, and reads the same pair on every later
  start; `GET /api/push/vapid-public-key` returns its public key.
- Push subscriptions are stored per account by a new migration; the subscription's
  `auth` secret is encrypted at rest with `TokenCipher`; `POST`/`DELETE
  /api/push/subscriptions` are scoped to the caller's own account, answer 401
  unauthenticated, and deleting an account removes its subscriptions.
- When an agent session becomes waiting with reason `bell`, the engine pushes one
  notification to every subscription of the owning project's owner — never for
  `quiet`, never for a shell — off the PTY drain thread; a `404`/`410` from the push
  service deletes that subscription, any other failure is logged and never
  propagates.
- The payload encryption reproduces RFC 8291 Appendix A's published test vector byte
  for byte, and the VAPID JWT verifies with the pair's public key.
- The client: turning "Notify me when an agent is waiting" on also subscribes
  through the service worker (when one is active) and registers with the engine;
  off unsubscribes and deregisters; a still-enabled preference re-registers on app
  start; the pushed notification carries the session id as its tag, is closed by
  the client when that agent is already on screen, and clicking it lands on the
  agent.
- ADR-114 records the decision.
- `./mvnw -B test` passes (engine and client suites).
- Human check on a real install: with the app closed on a phone or desktop, an
  agent ringing the bell produces a system notification, and tapping it opens that
  agent.

## Explicitly not
- Anything the in-app notification (#859) already covers while a tab is open.
- Suppressing a push while some tab is connected (see ADR-114's rationale).
- A second toggle, sound, badges, or per-project preferences.

## Decisions made along the way
- **JDK-only crypto, no dependency**: `WebPushEncryptor` (RFC 8291 over RFC 8188
  `aes128gcm`) and `VapidSigner` (RFC 8292, ES256 via the JDK's own
  `SHA256withECDSAinP1363Format`, so no DER-to-JOSE re-encoding) on `KeyAgreement`,
  `HmacSHA256` and `AES/GCM/NoPadding`. `engine/pom.xml` is untouched.
  `WebPushEncryptorTest` reproduces RFC 8291's worked example exactly; note the
  RFC's own `Content-Length: 145` is off by one (its plaintext is 41 bytes), the
  body it prints — and this reproduces — is 144.
- **Payload shape is `ngsw-worker.js`'s own**: `{notification: {title, body, tag,
  renotify, data: {sessionId, onActionClick: {default: {operation:
  "focusLastFocusedOrOpen", url}}}}}`. The worker shows it and, on click, focuses
  an open window (then the page's `SwPush.notificationClicks` handler routes to the
  agent through the shared `jumpTo`, selecting an issue's exact agent tab) or opens
  the URL when none is open — which for an issue's agent lands on the issue page
  with its remembered active tab, the accepted limitation the plan named. No custom
  service worker, so #859's recorded platform gap is not widened.
- **`SessionRegistry.addAttentionListener`** rather than a second subscription
  inside the attach path: a funnel alongside `addCloseListener`, consulted live at
  event time so a listener registered after a session was created still hears it;
  a throwing listener is contained on the drain thread.
- **`IssueTitles` is a one-method interface** wired in `PushConfig` from
  `ProjectGhResources`, so `PushNotifierTest` runs with a map instead of a `gh`
  client; a cold issue cache fetches once, on the push's own virtual thread.
- **Endpoint upsert re-homes ownership**: re-registering an endpoint the table
  already knows updates its `owner_user_id` — the endpoint is the browser's, and the
  account signed in to it now is whose notifications it should get.
- **`SwPush` is injected optionally** in both client services: absent (a test, or no
  `provideServiceWorker`) means push is simply `unavailable`, so every existing spec
  that constructs `NotificationService` is untouched; `PushService` syncs the
  subscription from an `effect` on the store's `enabled` signal, which is what makes
  a still-on preference re-register on start.
- **Settings copy**: the one toggle's description now says it also reaches a closed
  app on this device; two hints under it explain `unavailable` (no service worker,
  e.g. plain http) and `failed` (the browser refused — on iOS, add to Home Screen
  first). No second toggle.
- **`locklane.push.contact`** (the VAPID `sub` claim) defaults to this project's
  GitHub URL, overridable like every other `locklane.*` key.

## Deviations / notes
- **Re-planned twice by `/t-drive` itself**, both within the plan's own intent:
  `UserCascadeDeleteServiceTest.java` joined Allowed paths (it constructs the
  service whose constructor gained the subscription repository), and the pty test
  path was corrected to the new file's real name
  (`SessionRegistryAttentionListenerTest.java`).
- **Done-when pinned by the plan, replacing the issue's placeholder** ("to be pinned
  once the in-app notification has shown the bell signal is quiet"): the human's
  `/t-drive 860` was taken as that judgment; ADR-114 records it.
- **The real-device human check was not performed here**: this sandbox has no
  browser to subscribe with and no push service to reach, the same reason
  #855–#859 recorded. What was verified instead: the RFC 8291 vector, the JWT's
  signature against the public key, the request headers against a local stub
  server, owner scoping and 401s over MockMvc, and the client's subscribe /
  re-register / unsubscribe / close-when-viewed / click-to-agent flows against a
  faked `SwPush`. A human should confirm the real thing at the ship gate.
