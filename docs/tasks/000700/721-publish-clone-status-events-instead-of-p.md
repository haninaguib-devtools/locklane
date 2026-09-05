# 721 — Publish clone-status events instead of polling for them
Issue: #721

## Asked
The sidenav, the project console page, and the overview each re-read project status every 3 seconds while anything is cloning, because the server sends nothing when a clone settles. And a project deleted outside the current window leaves a ghost row until something else reloads the list, because nothing announces deletions either. Replace both gaps with a push: the engine publishes project-lifecycle events over the existing events channel, and each surface refreshes on receipt.

## Done when
- The engine broadcasts a project-status event when a clone reaches READY or FAILED, reusing the existing events channel and its established patterns (same shape as the issuesChanged / console-attention messages).
- The engine broadcasts a project-deleted event when a project is deleted, and the sidenav drops the row on receipt without a manual refresh (absorbed from #720).
- The sidenav cloning row, the console waiting state, and the overview cloning row all update off the status event; the 3s poll loops and their timers are gone from all three.
- Engine and client suites still pass, covering both broadcasts and each subscriber.

## Explicitly not
- Changing the CLONING/READY/FAILED status model itself: only how transitions are delivered.

## Decisions made along the way
- Both `READY`/`FAILED` transitions and both deletion paths (`delete`, `forceDelete`) route through one private helper each (`markReady`/`markFailed`/`deleteRow`) inside `ProjectCheckoutService`, so there is exactly one broadcast call site per event type despite ~15 `markFailed` call sites scattered through the clone/create-and-push paths.
- `ProjectCheckoutService` gained a package-private 6-arg convenience constructor defaulting to a no-op `EventBroadcaster`, mirroring the existing pattern in `ProjectGhResources`/`SessionRegistry` — this let all 13 existing same-package test call sites keep compiling unchanged; only `AdminUserControllerTest` (a different package) needed updating to pass an explicit `EventBroadcaster`.
- `forceDelete` (the cascade-delete path) also broadcasts `projectDeleted`, for consistency with `delete` — flagged in the plan as a human read item; not reverted, since CONSTITUTION.md §4.5 already scopes project visibility to the owner, so this is at most a same-owner, multi-tab effect.
- Event field names: `projectStatus` (`projectId`, `status`, `defaultBranch?`) and `projectDeleted` (`projectId`), following the existing `githubRefreshStatus`/`consolesChanged` shape in `events.service.ts`.
- Two pre-existing sidenav tests and one project-console test that only exercised the now-removed 3s poll ("polls again...", "does not poll...", "stops polling...", "stops the cloning poll...") were removed or rewritten to dispatch a `projectStatus`/`projectDeleted` event instead of ticking a fake clock — kept as regression coverage where the assertion ("no `/api/projects` request fires") still means something without the poll.

## Deviations / notes
- None — implementation followed the plan's Allowed paths and design as written.
