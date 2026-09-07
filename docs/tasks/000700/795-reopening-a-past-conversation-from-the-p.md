# 795 — Reopening a past conversation from the project page never resumes it
Issue: #795

## Asked
Clicking a past conversation on a project's page should open an agent tab that
continues that conversation. Today it never does: the user gets either a brand-new
blank agent (when the project had no open agent) or is dropped onto their most
recently used existing agent (when it had one). The conversation itself is intact —
its id is in Locklane's database and its transcript is on disk — so this is a handoff
defect, not data loss.

Since #752 the past-conversations list lives on the project page. Reopening one asks
the engine to mint a fresh session id (`<project>-console-<suffix>-resume-<short>`),
then navigates to the agent page with `?session=<new-id>&resume=<conversation-id>&
tool=<tool>`. That page maps those params onto the matching row of the engine's
open-agent list — but that list is built from `worktree_sessions`, which only gains a
row on a session's **first WebSocket attach**; the reopen endpoint records nothing.
The freshly minted id is never in the list, no terminal is mounted for it, and the
resume id is silently dropped. The #752 spec passed only because its mocked list
already contained the reopened id.

The issue offered two fixes — append the handed-off session client-side (mirroring
the issue page's own reopen path), or record the minted session engine-side at reopen
time — and asked the task to pick one and say why here (see Decisions).

## Done when
- From a project's page, clicking an entry in the past-conversations list opens a new
  agent tab whose first attach launches the tool's resume command (`claude --resume
  <id>` / `codex resume <id>` / `opencode --session <id>` / `omp --resume <id>`) and
  the previous conversation is visible in that tab. Human-judged, on a project that
  already has an open agent **and** on one that has none.
- `client/src/app/components/project-console/project-console.component.spec.ts` has a
  test where the mocked open-agent list does **not** contain the handed-off
  `?session=` id and the page still mounts a terminal for that id with `resume` and
  `cmd` set from the URL; the existing #752 test no longer relies on the list
  containing the reopened id.
- (Engine option only — not chosen, so not applicable: a `ProjectConsoleService` /
  controller test asserting a reopened session appears in `listOpen` before any
  attach.)
- Both `./mvnw -B test` and the client test suite pass; after a reopen on a live
  install, a `worktree_sessions` row exists for the `-resume-` session (manual check —
  with the client fix it appears at the first attach, which now actually happens).

## Explicitly not
- The issue-page reopen path (`MainContentComponent`), which already works — untouched.
- Expiry of conversations Claude Code has already purged (its `cleanupPeriodDays`
  default is 30 days): entries older than that are still listed and fail with "No
  conversation found" when reopened. Hiding or marking them is separate work.
- Any change to how resume ids are captured (`ResumeIdScanner`) or stored
  (`console_resume_sessions`).
- The engine's reopen endpoint, `worktree_sessions`, and the meaning of "open"
  engine-side — unchanged (the engine option was not chosen).

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- **Client fix, not engine fix** (Claude, 2026-09-07). The agent page now appends a
  handed-off `?session=` id that the open-agent list does not carry, provided the
  handoff also carries `?dir=` (the reopen response's `workingDirectory`), and
  selects it with `resume`/`tool` applied — exactly what the issue page's own
  `MainContentComponent.reopenSession` does by appending to its list directly. Why:
  (1) #370 already decided this shape for the sidenav "+": a freshly minted session
  must not round-trip through the open-agent list, which only knows attached
  sessions. (2) The engine option would change what "open" means (a row before any
  attach), leaving a phantom open tab whenever the user never lands on the page after
  a reopen, and — as `ShellSessionService.open`'s own comment explains for shells —
  would need the owner gate moved up to mint time; the issue itself flags those
  consequences (`hasLiveSessionIn`, the sweeper, `find`'s wording). (3) Engine-side a
  reopened session's *resume intent* still lives only in the URL, so pre-recording the
  row buys nothing the first attach does not already record.
- **`?dir=` is the signal that a handoff is fresh** (Claude, 2026-09-07). A
  `?session=` naming an id absent from the list with no `?dir=` keeps falling back
  to the most recently attached agent, as before (a stale bookmark, a closed tab —
  the existing "falls back … when ?session names a closed one" spec). The directory
  has to travel anyway: the WebSocket attach resolves a never-attached session's
  working directory from the client's `dir` param, with no engine-side fallback.
- **"Open agent" gets the same handoff** (Claude, 2026-09-07). The project page's
  "Open agent" button, when no agent is open, mints a session and hands off its id
  the same way — and the agent page, finding it absent, auto-started a *second* one,
  stranding the first worktree. The issue names this as what the client fix also
  makes honest; `project-summary/` is in Scope, so `startConsole` now carries `dir`
  too and lands on the session it minted.
- **The one-shot handoff params are dropped from the URL once consumed** (Claude,
  2026-09-07). `dir`, `resume` and `tool` are rewritten out of the URL (keeping
  `session`, the ordinary tab-activation handoff) the same way `?new` is dropped
  (#370): otherwise a reload after closing that tab would append a phantom tab and
  re-run the resume command against a session id the engine had already ended.

## Deviations / notes
- none
