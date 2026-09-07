# 791 — Blue-amber attention dot on agent tabs, backed by one shared attention store
Issue: #791 · Part of: #789

## Asked
The agent tab strip should show, per tab, whether that agent is waiting for the user:
a small blue dot on every open agent tab (the same blue as the sidenav's per-issue dot,
`#3b6fa8`), turning amber and pulsing while that agent's session is waiting for
attention, exactly as the sidenav dot does today. Selecting the tab already tells the
engine the session is focused, which clears the waiting state, so the dot settles back
to blue on its own. Two components already track "which sessions are waiting" by keeping
a private `Set<string>` fed from the events channel's `consoleAttention` events: the
sidenav (`waitingSessions`) and the header badge (`console-indicator`). Rather than add
a third copy in the tab strip, this task introduces one shared, injectable attention
store that subscribes to the events channel once and exposes whether a given session id
is waiting; all three indicators read from it. A tab's id is the agent's session id, the
same id the event carries, so the tab strip needs no lookup. The store also picks up the
connect-time snapshot the sibling engine task adds, since that arrives as ordinary
`consoleAttention` events.

## Done when
- A new `client/src/app/services/attention-store.ts` (with a spec) is the only client
  code that subscribes to `consoleAttention` events; it exposes a reactive
  `isWaiting(sessionId)` (signal-based, so templates update without manual change
  detection) and applies `waiting`/`active` events by session id.
- `sidenav.component.ts` and `console-indicator.component.ts` no longer hold their own
  waiting sets or their own `events$` attention subscriptions; `hasAttentionWaiting`,
  `hasAttentionWaitingForProject` and `hasWaitingEntry` keep their names and behaviour,
  backed by the store, and their existing specs pass unchanged (they drive state by
  emitting `consoleAttention` events, which the store now consumes).
- `console-tabs.component.html` renders a dot inside each agent tab's label (not the
  Overview tab), with class `waiting` while the store reports that session waiting; the
  styling in `console-tabs.component.css` is blue by default and amber with the same
  pulse animation the sidenav uses while waiting. The blue is lifted into a shared token
  in `client/src/styles.css` used by both the sidenav dot and the tab dot, so the two
  cannot drift apart.
- The waiting tab's button carries `title="Waiting for you"` and its accessible name
  says so, so the state is not colour-only.
- The strip still constructs bare in its own specs (`new ConsoleTabsComponent()`): the
  store is an `@Optional()` constructor dependency like the services already injected
  there, and a null store means "never waiting".
- `console-tabs.component.spec.ts` covers: dot present on every agent tab, absent on
  Overview, `waiting` class follows the store for that session only.
- Both call sites of the strip (issue page `main-content` and the project agents page
  `project-console`) show the dot with no change to their own templates beyond what the
  shared component provides.
- `./mvnw -B test` passes (the client build and its Karma suite run inside it).

## Explicitly not
- Changing the header badge's own dot colour (green today) or its behaviour beyond
  reading from the shared store.
- The engine-side connect snapshot; that is the sibling task under the same initiative
  (#790), and this task's store works from live events alone.
- Any change to what the sidenav dot looks like or when it shows.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The store keeps one signal holding the set of waiting session ids (`waiting()`), with
  `isWaiting(id)` reading through it, rather than one signal per session: the sidenav
  keys its two indicators by issue and by project, not by session id, so it needs to
  walk the waiting ids and classify each with the same session-id parsers it already
  used (`projectIssueKeyFromSessionId`, `projectIdFromProjectConsoleSessionId`). The
  tab strip and the header badge only ever ask about one id (agent, 2026-09-07).
- Consequence for the sidenav's per-issue dot, noted rather than hidden: it used to key
  waiting state by `"<projectId>:<issueNumber>"`, so with two sessions on the same issue
  one going `active` cleared the issue's dot even while the other still waited. Backed
  by per-session state the dot now stays amber while any session of that issue waits.
  No existing spec covers that case; the specs pass unchanged (agent, 2026-09-07).
- The pulse keyframes are declared again in the tab strip's own stylesheet (same name,
  same 1.2s timing and 35% dip) rather than lifted to `styles.css`: Angular's emulated
  view encapsulation scopes a component's `@keyframes`, and the Scope line lifts only
  the blue colour into the shared token (`--agent-dot`) (agent, 2026-09-07).
- The tab dot is a purely decorative `aria-hidden` span; the state reaches assistive
  technology through the tab button's `aria-label` (`"<label>, waiting for you"`) and
  its `title="Waiting for you"`, which takes precedence over the rename hint while the
  tab waits (agent, 2026-09-07).

## Deviations / notes
- none
