# 729 — Fix sidenav stuck on cloning after import settles
Issue: #729

## Asked
Importing a project shows a cloning row in the sidebar, and once the engine finishes
cloning the sidebar keeps showing cloning until a full page reload, while the main
project list correctly flips to ready. Make the sidebar row settle on its own.

## Done when
- A regression test reproduces the import order (sidebar reload in flight when the
  clone-settled broadcast arrives) and fails before the fix: the sidebar row ends
  READY, not CLONING.
- When a clone settles, the sidebar row for that project updates to READY/FAILED
  without a page reload and without re-polling, and a newly READY row loads its
  issue tree so it does not sit empty.
- Existing client checks for the touched area pass (sidenav specs) plus the repo
  check set that applies to the diff.

## Explicitly not
- No change to the CLONING/READY/FAILED model itself.
- No return of polling.

## Decisions made along the way
- Root cause: `revealProject` triggers a full reload; the engine's `projectStatus`
  broadcast can arrive while that reload is in flight (the list already answered
  CLONING, the row does not exist in `sections` yet), so
  `applyProjectStatusEvent` found no row and dropped the event. Fix: hold such an
  event per project until a reload lands, then apply it; drop it on
  `projectDeleted`. (agent, 2026-09-05)
- A row that flips to READY re-fetches its tree once (the tree fetched while
  CLONING is empty) — one request on the event, no timer. (agent, 2026-09-05)

## Deviations / notes
- Existing #721 specs that emit READY now flush that one tree fetch; the #721
  "ignored when not loaded" spec was reworded, since the event is now held rather
  than ignored, and still asserts loaded rows are untouched.
- `./mvnw -B test` locally: every client test passes (753); 14 engine failures are
  this machine's known environmental ones (git-worktree persistence tests, a
  missing `/bin/true`), identical outside the sandbox and in modules this diff
  does not touch. CI's run is the authoritative one for the engine module.
