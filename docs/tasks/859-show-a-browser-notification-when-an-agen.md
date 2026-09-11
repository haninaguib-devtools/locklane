# 859 — Show a browser notification when an agent rings the bell and its tab is not in view
Issue: #859 · Part of: #853

## Asked
Show a browser notification when an agent rings the bell and its tab is not in view. The client's attention store already knows which sessions are waiting and, once the reason field lands, whether it was a bell or the quiet-output fallback. A new notification service subscribes to it and, on a session becoming waiting with reason `bell`, shows one notification through the service worker registration the PWA already has (falling back to the Notification constructor where no registration is active), so it works with the tab in the background or the window minimised. It stays silent when that session is the active tab and the document is visible, since the engine's rule fires even for the tab in view, and it never notifies for shell tabs or for `quiet`. The text uses the same entry model the header indicator builds: title "Agent on #<issue> is waiting" with the issue title as the body, or the project name for a project agent; the word is always *agent*, never *session* (ADR-112). Clicking focuses the window and navigates to that agent the way the header indicator does, which sends the focus frame and clears the waiting state; the notification is tagged by session id so a repeat replaces rather than stacks, and it is closed when the session goes active. The feature is off until the user turns it on from a toggle in the settings dialog, "Notify me when an agent is waiting", which requests browser permission at that moment and never on page load; the preference is a small localStorage store like the other preference stores, and the toggle explains itself when permission was denied.

## Done when
- `client/src/app/services/notification.service.ts` (with a spec) is the only place that calls the browser notification API; specs cover: bell → notification; quiet → none; shell tab → none; active visible tab → none; session goes active → notification closed; click → navigation to the agent.
- The settings dialog carries the toggle; enabling it requests permission; the state survives reload via a `notifications-store.ts` following the existing store pattern; a denied permission is shown on the toggle, not thrown.
- The navigation logic the header indicator uses is shared with the service rather than duplicated.
- Manual check in a real browser: with the toggle on and a different tab in view, an agent ringing the bell produces a system notification, and clicking it lands on that agent with the dot cleared.
- The client test suite passes.

## Explicitly not
- Notifications when the app is closed (Web Push).
- Any engine change.
- Sound, badges or per-project preferences.

## Decisions made along the way
- **Shared entry/navigation extraction** (Scope's `agent-session-indicator/`): pulled
  `AgentSessionEntry`, `fetchEntries(projects)`, and `jumpTo(entry)` out of
  `AgentSessionIndicatorComponent` into a new `AgentSessionEntriesService`
  (`client/src/app/services/agent-session-entries.service.ts`) — not itself named by
  the issue, but the natural home for logic extracted *out of* a file the Scope does
  name, sitting alongside the other services the indicator and the notification
  service both already depend on. `AgentSessionGroup` (the picker's own
  project-grouping shape) stayed in the component; nothing outside it groups entries.
  `AgentSessionEntry` gained a `projectName` field (the bare name, no "Project - "
  prefix) since the notification's own body text needs it undecorated — the
  indicator's existing spec assertions were updated for the new field.
- **`AttentionStore.changes$`** (Scope's `attention-store.ts`): added one `Observable`
  of genuine waiting/reason transitions, reusing `apply()`'s own existing dedup
  rather than have the notification service re-derive "did this actually change"
  from the raw event stream a second time. `isWaiting`/`reason` are unchanged.
- **Notification content**: "Agent on #<issue> is waiting" with the issue's own
  title as the body for an issue's agent session; the issue's phrasing for a project
  agent session ("...or the project name for a project agent") only names the body,
  not a second title format, so a project agent session's title is the plain "Agent
  is waiting", with the project's bare name as the body — the reading that avoids
  repeating the project name in both fields.
- **"Active tab, document visible" check**: no existing reactive signal already
  tracks "which agent session is on screen in this window," so this is derived
  directly in `NotificationService` from `Router`/`ActivatedRoute` snapshot state
  (mirroring the same private technique `AppComponent`/`CurrentProjectService`
  already use for their own current-issue/current-project reads) plus
  `ActiveAgentSessionStore` for which of an issue's agent sessions is the active
  tab. `document.visibilityState !== 'visible'` alone is not the "silent" case: the
  session must also be the *specific* one on screen.
- **No background entries cache**: an early version kept `NotificationService`
  continuously re-fetching every open agent session's entry in the background, the
  same reactive shape the indicator uses. Dropped for two reasons found while
  writing this component's own spec: (1) it fetches this data from every project on
  every open/close/rename regardless of whether a bell ever rings, duplicating the
  indicator's own traffic for no benefit since nothing reads the cache except a bell
  handler; (2) merely *constructing* the service (which happens the moment
  `AgentSessionIndicatorComponent` mounts) started an unconditional background fetch
  cycle, which is wasted work and, worse, made every indicator-component test that
  merely mounts the component also assert against unrelated HTTP traffic. Replaced
  with fetching one session's `AgentSessionEntry` fresh, on demand, only at the
  moment a bell actually passes the enabled/permission checks -- `CurrentProjectService`
  itself is also read through a lazy `Injector.get()` getter rather than an eager
  field, the same reason `AppComponent`'s own `currentProject` getter exists, so
  `NotificationService` fetches nothing at all until a bell genuinely needs it.
- **`show()`'s two paths**: `navigator.serviceWorker.getRegistration()` (not
  `.ready`, which would hang forever with no SW ever registered, e.g. in a dev
  build) decides between `registration.showNotification()` and
  `new Notification()`. Click handling is only wired through the `Notification`
  constructor path (`.onclick`) — see Deviations below for why the
  service-worker-registration path's click can't be wired the same way from this
  codebase.

## Deviations / notes
- **A genuine platform gap, not a workaround**: a notification shown through
  `ServiceWorkerRegistration.showNotification()` delivers its `notificationclick`
  event to the *service worker's own* global scope, never to the page — there is no
  page-side listener for it. Wiring click-to-navigate for that path would need a
  custom `notificationclick` handler inside the service worker script itself, but
  this project's service worker (`ngsw-worker.js`) is generated by Angular's own
  build tooling with no supported hook for one. `close()` still works for that path
  (`registration.getNotifications({tag}).forEach(n => n.close())` needs no such
  handler), and `show()` itself still uses the registration when one is active — only
  the click-to-navigate behaviour is unavailable there. Confirmed empirically in this
  sandbox that Karma's own headless Chrome test run always takes the `Notification`
  constructor path in practice (no real service worker is ever registered in a test
  or dev build), so every spec here exercises that fully-wired path; the
  registration path's *display* (not its click) still wants a real-browser PWA
  install to confirm, per the manual check below.
- The Done-when's manual check — "with the toggle on and a different tab in view, an
  agent ringing the bell produces a system notification, and clicking it lands on
  that agent" — was **not completed in a real browser**, the same sandbox reason
  recorded on #855/#856/#857/#858 (no controlling terminal, and critically here, no
  actual browser window to receive a real system notification or exercise Notification
  permission prompts at all). What was verified instead: the full logic — bell → shown,
  quiet → none, shell → none, active+visible → none, active-elsewhere → still shown,
  session-goes-active → closed, click → focus+navigate+close, preference/permission
  gating — against a faked `Notification` global and a mocked `serviceWorker.getRegistration()`,
  covering every case the Done-when names. A human should confirm the real thing: that
  a real OS notification actually appears with the toggle on, and that the
  service-worker-registration path (if the app is running as an installed PWA) behaves
  reasonably even though its click cannot navigate per the platform gap above.
