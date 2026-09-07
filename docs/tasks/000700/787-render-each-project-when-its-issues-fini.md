# 787 — Render each project when its issues finish loading
Issue: #787

## Asked
Show each project in the sidenav as soon as that project's issue-tree request
finishes, so one slow project no longer keeps every other project invisible during
initial load or refresh.

## Done when
- After the project list loads, issue-tree requests may still run concurrently, but
  each completed project section becomes visible without waiting for all requests to
  complete.
- A slow or failed issue-tree request for one project does not delay or remove
  successfully loaded project sections.
- Project ordering remains stable and matches the project-list order as sections
  arrive.
- Loading, refresh, focused-window, queued-refresh, selection/focus, GitHub-status,
  console-indicator, and live-event behavior remain coherent while only part of the
  project list has loaded.
- The UI gives an appropriate per-project loading or failure state rather than
  treating one request failure as a failure of the entire sidenav.
- Automated client tests cover out-of-order completion, partial failure, stable
  ordering, and focused-project loading.

## Explicitly not
- Changing when or how the engine warms or refreshes its GitHub cache.
- Persisting issue data in the browser or engine.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Sections are built in project-list order the moment the list arrives, each with
  its own `treeState` (`loading` → `loaded` | `failed`); every tree request then
  writes into its own section by project id. Ordering therefore never depends on
  response order (agent, 2026-09-07).
- A reload carries over the tree of any section that had already loaded, so a
  refresh keeps showing the rows it has until the new tree lands — the same
  in-place update the old all-at-once `forkJoin` gave, now per project. A section
  that had never loaded, or whose last fetch failed, starts over as `loading`
  (agent, 2026-09-07).
- `refreshing` (and the queued refresh behind it, #738) still waits for every tree
  request to settle, success or failure: the refresh button spins for the whole
  refresh, and a queued run starts only once nothing from the previous one is in
  flight, exactly as before (agent, 2026-09-07).
- A failed project-list request is still the whole-sidenav error state
  (`could not load issues`), unchanged: with no list there is nothing to render per
  project. Only tree requests became per-project (agent, 2026-09-07).
- Tree responses from a load whose sections have since been replaced by a newer
  load are dropped rather than written by id: the newer load has its own request
  for that project in flight, and an older response landing later would otherwise
  overwrite the fresher tree (agent, 2026-09-07).
- Clone-settled events held for a project not yet listed (#729) are applied as soon
  as the list carries it, not after every tree lands; the READY row's real-tree
  re-fetch (#729) is deferred until its own in-flight tree request lands, so the two
  never race and no duplicate request is sent (agent, 2026-09-07).
- Open-agent indicators are fetched as soon as the list arrives rather than after
  every tree, so a project's rows carry their dots the moment they render
  (agent, 2026-09-07).

## Deviations / notes
- A failed `refreshProject` re-fetch (an `issuesChanged` event, a stale
  notification, a READY transition) previously went unhandled — it surfaced only
  as an Angular ErrorHandler log and the section kept its old tree silently. It now
  marks that section `failed` while keeping the old tree, the same per-project
  failure state the initial load uses. Within the task's Scope (sidenav tree
  loading); noted here since it changes existing behavior.
- Two existing specs were reworded rather than deleted: "reports an error state when
  a tree fetch fails" now asserts the per-project failed state, and "refresh()
  surfaces an error without clearing the existing list" now fails the *list* request
  (its original intent, which a tree failure no longer exercises).
