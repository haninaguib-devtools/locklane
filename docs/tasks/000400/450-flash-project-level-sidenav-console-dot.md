# 450 — Flash project-level sidenav console dot on attention
Issue: #450

## Asked
A project's sidenav row shows a small dot when it has one or more open project-level
consoles (consoles at the project root, not tied to an issue). That dot stays static
blue even when one of those consoles is waiting for input, because
`applyAttentionEvent()` in `sidenav.component.ts` parses the incoming event's session
id with `projectIssueKeyFromSessionId()`, which only recognizes issue-shaped ids
(`<projectId>-<issueNumber>-...`). A project-level console's id
(`<projectId>-console[-suffix]`) parses to `null` and the event is silently dropped.
Fix it so the project row's dot pulses amber (same visual as issue rows) when any of
that project's project-level consoles needs attention — mirroring how the static dot
already tracks project-level consoles (`openConsoleProjects` /
`isProjectConsoleSessionId`) and how the header's `console-indicator` keys its
waiting-state set off raw session ids rather than a parsed project/issue key.

## Done when
- A project-level console entering the waiting state switches its project's sidenav
  dot to the pulsing amber "needs attention" style used by issue-level dots.
- The static blue "has open project console" dot is unaffected when no project-level
  console is waiting.
- Issue-level dot behavior (`hasAttentionWaiting` / `waitingIssues`) is unaffected.
- A unit test in `sidenav.component.spec.ts` exercises `applyAttentionEvent` with a
  project-console-shaped session id and asserts `hasAttentionWaitingForProject(...)`
  becomes true, alongside the existing issue-shaped coverage.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Reworking how the header's `console-indicator` component tracks waiting state — it
  already handles project-level console session ids correctly.
- Backend changes — `SessionRegistry`/`PtySession` already emit the event correctly
  for project-level console sessions.

## Decisions made along the way
- `hasAttentionWaitingForProject()` now reflects project-level console waiting state
  *exclusively*, no longer scanning `waitingIssues` (issue-level keys) by project
  prefix (agent, 2026-08-31). Rationale: #330's triage established the project dot
  "must track project-level consoles exclusively" for the open-console state, and the
  issue's Goal says to mirror exactly that tracking; the old prefix scan could make
  the project-console dot pulse for a *issue*-level wait, with a tooltip claiming a
  project console needs you — misleading, since each issue row's own dot already
  covers those. The now-unused `anyKeyForProject` helper is removed with it.
- Waiting project-level consoles are tracked as raw session ids
  (`waitingProjectConsoleSessions`), not as project ids, mirroring the header's
  `console-indicator` (agent, 2026-08-31): with two project consoles open, one going
  `active` must not clear a project-id flag another still-waiting console set.
- Added `projectIdFromProjectConsoleSessionId()` to `consoles.service.ts` (the
  "project-console-aware variant" the issue's Scope anticipated), so the component
  can place a project-console session id onto its project without a second regex.

## Deviations / notes
- The task branch was created from `origin/main` (1901ed4) rather than the local
  `main` ref: local `main` (662e175) is behind-only, but it is checked out in the
  primary worktree, which may belong to another session, so it was not fast-forwarded
  from here. Diff-base checks in this task use `origin/main` accordingly.
