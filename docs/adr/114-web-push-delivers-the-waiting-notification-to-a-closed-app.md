# ADR-114: Web Push delivers "agent is waiting" to a closed app, from the engine, on JDK crypto alone

**Status:** Accepted · 2026-09-10
**Deciders:** project owner *(solo phase; ratified by the human's confirmation at
`/t-ship`'s gate on task #860, split from initiative #853)*

## Context

Initiative #853 made an agent's bell a precise, agent-agnostic "waiting for you"
signal (#855–#858, ADR-113) and showed it as a browser notification while a
Locklane tab is open but not in view (#859). That notification is drawn by the page,
so it stops the moment the app is closed — on a phone in a pocket, or a laptop with
the tab gone — which is exactly when a person most needs to hear that an agent has
stopped and is waiting.

The only mechanism a browser offers for that is Web Push (RFC 8030): the browser's
push service holds a per-browser subscription — an endpoint URL plus a P-256 public
key and a 16-byte authentication secret — and the *application server* posts an
encrypted message (RFC 8291) to that endpoint, authenticated by a signed VAPID token
(RFC 8292) proving it holds the key pair the subscription was created with.
Delivering it therefore requires three things Locklane's engine has never done or
held: an outbound call to a third-party service (Google's, Mozilla's or Apple's push
service, whichever the browser uses) on the user's behalf; a long-lived key pair that
is the engine's identity to those services; and a per-user secret — the subscription's
`auth` — that lets anyone holding it, together with the key pair, push to that
person's device.

Two further constraints shape the design. ADR-105 makes a project visible only to its
owner, so a bell may reach only the browsers of the account that owns the agent's
project. And the engine's dependency set is deliberately small (`engine/pom.xml`): the
only maintained Java Web Push library pulls in BouncyCastle and a second HTTP client
for what is, in the end, one ECDH agreement, two HKDF derivations, one AES-GCM
record and one ES256 signature — every one of which Java 21 already provides.

## Decision

1. **The engine pushes; the service worker shows.** On a session becoming waiting
   with reason `bell` — and only then: never for the `quiet` fallback, never for a
   shell — `PushNotifier` posts one encrypted message to every subscription of the
   owning project's owner. Angular's own `ngsw-worker.js` shows the payload's
   `notification` and acts on its `onActionClick` without any custom worker code;
   the payload is tagged with the session id so it replaces the in-app notification
   for the same agent rather than stacking.
2. **One VAPID key pair per engine, in a file, never in the database.**
   `VapidKeyPair` generates it on first start into `<data-dir>/push-vapid.key`,
   owner-only, and loads it on every start after — the same shape and reasoning as
   `EncryptionKeyProvider`'s file: a copy of `locklane.db` alone must not be enough
   to push to anyone's device.
3. **Subscriptions are per account, and the secret is encrypted at rest.** A new
   `push_subscriptions` table (V18) carries `owner_user_id`; the `auth` secret is
   encrypted by `TokenCipher` like a GitHub token. The REST surface
   (`/api/push/**`) is gated as authenticated in `SecurityConfig` and scoped to the
   caller's own rows; deleting an account deletes its subscriptions
   (`UserCascadeDeleteService`), since SQLite enforces no foreign key here.
4. **JDK crypto only, proven against the RFC's own vector.** `WebPushEncryptor` and
   `VapidSigner` use `KeyAgreement("ECDH")`, `HmacSHA256`, `AES/GCM/NoPadding` and
   `SHA256withECDSAinP1363Format`, nothing else; no dependency is added. The
   encryptor takes its salt and ephemeral key injectably so a test reproduces
   RFC 8291's worked example byte for byte — the one check that proves the
   derivation matches what every push service expects.
5. **One preference, both paths.** "Notify me when an agent is waiting" (#859) is
   the only toggle: while it is on, `PushService` keeps this browser subscribed —
   including on every app start, since browsers silently drop subscriptions — and
   while it is off, unsubscribes on both sides. The engine prunes a subscription the
   first time its push service answers 404 or 410.
6. **No suppression while a tab is open.** The engine cannot see focus, so it pushes
   on every bell; a page that *is* open closes the pushed notification again when
   it is for the agent on screen and visible, and routes a click on one to the agent
   through the same `jumpTo` the in-app click uses.
7. **Nothing slow on the drain thread.** `SessionRegistry.addAttentionListener` is a
   new funnel alongside `addCloseListener`; the notifier hands each push to a
   virtual-thread executor and contains every failure, the same guard the resume-id
   scanner already has.

## Rationale

- **The payload carries a title, a body and a route, and nothing else.** No code,
  no repository content, no token crosses to the push service, and it is encrypted
  end to end besides; what the service learns is that *someone* on this engine has
  an agent waiting. That is the whole of what "calling an external service on the
  user's behalf" amounts to here, and it is opt-in per browser.
- **A file-held key pair follows an existing precedent** rather than inventing a
  second secret-storage story; losing the file costs each browser one re-subscribe,
  which decision 5's re-registration on start does unprompted.
- **Owner-scoping is the only tenancy rule there is** (ADR-105): a bell reaches the
  project owner's browsers because the engine looks the owner up, not because the
  subscription was created from a tab that happened to be on that project.
- **Hand-rolled RFC 8291 is ~120 lines against a published vector**, versus a
  library last released in 2021 that brings BouncyCastle into a jar that has never
  needed it. The vector, not the author's confidence, is what makes this acceptable;
  the real-device human check at the ship gate is the second half of the proof.
- **Not suppressing while connected** is deliberate: the obvious alternative —
  push only when the owner has no open events connection — would silence the phone
  whenever a desktop tab is open all day, which is the ordinary case. A brief
  duplicate on the tab in view is the cheaper wrong.

## Alternatives considered

- **`nl.martijndwars:web-push` (or similar)** — rejected: BouncyCastle plus its own
  HTTP client for four JDK-native primitives, and a release cadence that stopped
  years ago.
- **A custom service worker with `push`/`notificationclick` handlers** — rejected:
  `ngsw-worker.js` already shows a `notification` payload and acts on
  `onActionClick`, and Angular's build offers no supported hook to add code to it
  (the gap #859 recorded); the payload shape decision 1 uses makes that hook
  unnecessary.
- **Push only when the owner has no live events connection** — rejected, as above.
- **Storing the VAPID pair in the database** — rejected: same reasoning that keeps
  the encryption key out of it (#47).
- **A second toggle for push** — rejected: the person's intent is "tell me when an
  agent is waiting"; where the notification is drawn is the app's business.
- **Deferring until the bell signal is proven quiet in daily use** — the issue's
  original precondition; the human's `/t-drive 860` on 2026-09-10 was taken as that
  judgment, recorded here so the ordering is not mistaken for an accident.

## Consequences / revisit triggers

- `engine/pom.xml` is unchanged; `docs/architecture` gains nothing — this ADR is the
  reference for the push surface.
- The migration (V18) and `SecurityConfig` are reserved protected surfaces
  (CLAUDE.md constraint 3) not yet enforced by `protected-paths.sh`; this task took
  a cold review regardless.
- A subscription's endpoint is the browser's, not the account's: signing a different
  account in to the same browser and turning the toggle on re-homes the endpoint to
  that account (an upsert on `endpoint`), which is the correct reading of "this
  browser's notifications belong to whoever is signed in here".
- Signing out does not drop the subscription -- only the toggle does, on purpose:
  the preference is the browser's, and a person who signs back in expects to still
  be notified. On a shared device, turning the toggle off before signing out is the
  way to stop it; revisit if that proves to be a real footgun.
- iOS requires the app added to the Home Screen before a push subscription is
  granted; the settings dialog says so when the browser refuses. Revisit if Apple
  lifts that.
- If a future notification needs more than a title, a body and a route — actions,
  images, a payload the page must decrypt itself — decision 1's "no custom worker"
  is the first thing to reopen.
- If the RFC vector ever stops passing after a JDK upgrade, that is a provider
  change in the JDK, not a reason to reach for a library first.
