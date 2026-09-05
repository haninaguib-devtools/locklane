# 709 — Update template to v0.0.14
Issue: #709

## Asked
Sync this repo's template-owned files forward from t-workflow tag `v0.0.13` to `v0.0.14`
with `/t-update`, as one ordinary task. Two template changes land in that range, both
fixing problems this repo hit and reported upstream: t-workflow#127 (`/t-work`'s blocker
gate now recognises a sibling blocker that `/t-drive` has already merged into the
initiative's integration branch instead of refusing to start the child — ADR-009; this
repo hit that refusal three times in driven runs) and t-workflow#126 (a consumer names
its own required status checks in `.t-workflow/required-checks.local`, and
`github-bootstrap.sh` unions them with the template's `checks` + `cold-review` instead
of wiping them — the path by which the real-macOS lifecycle job from #705 can become a
required check).

Files: 2 added (`.t-workflow/scripts/required-checks.sh`,
`docs/adr/009-driven-sibling-blockers-satisfied-by-integration-merge.md`), 9 changed,
45 unchanged, 0 removed from the owned list. Migrations applied: V2 (relocates any
hand-set required context from the forge into `.t-workflow/required-checks.local`; the
live contexts on `main` were only `checks` and `cold-review`, so nothing was relocated
and no file was created).

## Done when
- `.template-manifest.json` pins `v0.0.14` with every owned file's normalized hash;
  `migrations_applied` is 2.
- `.t-workflow/scripts/check-manifest.sh` passes against the synced tree.
- `.github/workflows/ci.yml` local slots are byte-identical to before the sync.
- `.t-workflow/scripts/required-checks.sh --list` prints `checks` and `cold-review`
  (V2's Done-when).
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No changes to non-template files, application code, or local-slot content.
- Does not make the real-macOS lifecycle job a required check — a one-line follow-up
  in `.t-workflow/required-checks.local` once this sync has shipped.

## Decisions made along the way
- none

## Deviations / notes
- The task branch was created from `origin/main` rather than a fast-forwarded local
  `main`: local `main` is checked out in the primary worktree, so it cannot be moved
  from this checkout; this checkout's detached HEAD was already equal to `origin/main`.
- Sequencing: `/t-update` applied the sync, migration V2 and the manifest and committed
  them before the issue carried a `## Plan`; `/t-work` Phase 3's plan gate then stopped
  it short of pushing and opening the draft PR (same gap as #650). `/t-plan 709` adds
  the plan; `/t-work 709` resumes on this branch to push and open the PR.
- Fix pass after the cold review (PR #710, finding 1, high): the first manifest listed
  54 files, omitting the two this sync added (`required-checks.sh`, ADR-009). Cause:
  `/t-update` step 8's file list was taken from this repo's own
  `template-owned-paths.sh --list`, which narrows to the *current* manifest's keys
  (the v0.0.13 fix for the false positive) and so cannot see files that are new at the
  target tag — the list has to come from the scratch clone of the tag, as step 4 does.
  Regenerated the manifest from the clone's list (56 files). The plan's "(54 paths)"
  and `len==54` check carried the same error and were corrected to 56 by a re-plan;
  Allowed paths unchanged. Worth raising upstream as a `/t-update` step 8 defect.
