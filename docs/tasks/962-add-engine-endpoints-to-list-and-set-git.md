# 962 — Add engine endpoints to list and set GitHub issue labels
Issue: #962 · Part of: #961

## Asked
Add engine support for reading the repo's full label set and for writing an issue's labels, both backed by the `gh` CLI, following the existing pattern in `CliGhClient` (which today only reads issues via `gh issue list`). This gives the client something to call for the new label-assignment UI (tracked separately).

## Done when
- An engine endpoint returns every label defined in the repo (name + color), via `gh label list --json name,color` — not just labels currently in use on loaded issues.
- An engine endpoint adds/removes labels on a given issue, via `gh issue edit --add-label`/`--remove-label`.
- Both are covered by tests exercising the new `CliGhClient` methods (or equivalent) against a fake process runner, following the existing test pattern for that class.

## Explicitly not
- Any client-side UI work — see the sibling "Labels" UI task.
- Creating, deleting, or recoloring labels themselves — that stays on GitHub.

## Decisions made along the way
- `GhClient.labels()`/`updateIssueLabels()` are added as **default** interface
  methods that throw `UnsupportedOperationException`, rather than new abstract
  methods. The interface has many test-only implementations across both the
  `github` and `persistence` packages that never need label support; making
  the new methods abstract would force edits to all of them, well outside this
  task's scope. `CliGhClient` and the no-checkout stand-in in
  `ProjectGhResources` override them for real.
- Repo labels are served live via `GET /api/projects/{projectId}/issues/labels`
  (uncached, like `pullRequestDetail`) since the label set is small and rarely
  read. Adding/removing is `PATCH /api/projects/{projectId}/issues/{number}/labels`
  with a `{add, remove}` body; on success it refreshes `GhIssueCache` and
  broadcasts `issuesChanged` on change, mirroring the existing `tree(fresh=true)`
  refresh-and-broadcast pattern so other open tabs pick up the new labels.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
