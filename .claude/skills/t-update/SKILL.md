---
name: t-update
description: Sync this consumer repo's template-owned files forward to a pinned template release — refuses on a dirty owned file, preserves `<!-- local -->` slots, applies pending migrations in order, and lands the whole update as one ordinary pipeline task (its own issue, branch, record, checks, draft PR). Use to update, sync, or pull in template changes.
---

# Sync to a pinned template release

`docs/architecture/manifest.md` is the format this skill reads and writes;
`docs/architecture/migrations.md` is the convention for the breaking-change files it
applies; `docs/architecture/local-slots.md` is what it must never overwrite. Resolve
every `tracker:*` / `forge:*` operation via `docs/adapters/TRACKER.md` and
`docs/adapters/FORGE.md`. **No fast path**: this issue's own words apply here as much
as anywhere — a sync that touches a protected surface (almost every one does, since
template-owned files are protected surfaces by construction) still needs the plan and
independent review those surfaces always need. This skill never merges anything itself.

In this repo (the template, not a pinned consumer) there is no `.template-manifest.json`
to update — this skill exists for the repos generated from it.

## Procedure

1. **Read the current pin.** `.template-manifest.json` at the repo root. None found
   means first adoption: there is no "current owned file" to check for dirt, and the
   template repo is read from `README.md`'s `Generated from t-workflow @ <ref> — <url>`
   line (`installer/bootstrap.sh` stamps it at genesis) unless the human named one.

2. **Resolve the target.** The tag the human named, or the template repo's latest tag
   otherwise. Fetch the template at that tag into a scratch clone
   (`mktemp -d`, `git clone --depth 1 --branch <tag>`) — never inside this repo's own
   working tree; nothing here is touched yet.

3. **Refuse if dirty.** Skip this step on first adoption (there is nothing to compare
   against). Otherwise, for every path the current manifest lists, recompute its
   normalized hash from this repo's working tree
   (`.t-workflow/scripts/check-manifest.sh --hash-file <path>`) and compare to the manifest's
   recorded value for the *current* pin. Any mismatch means the file was hand-edited
   outside its slot since the last sync — **stop, list every offending path, and go no
   further.** The fix is upstreaming that edit as its own change first, never
   overwriting it here. Equivalently: `.t-workflow/scripts/check-manifest.sh` itself failing against
   the current tree is the same refusal, reached the same way.

4. **Compute what would change**, entirely from the scratch clone and this repo's
   working tree — nothing written yet:
   - the new file list: `.t-workflow/scripts/template-owned-paths.sh --list`, run **in the scratch
     clone** (the target tag's own view of what's template-owned, which may have grown
     or shrunk);
   - for each file: added (new in the list), changed (normalized hash differs from the
     current manifest, or first adoption and the file doesn't exist here yet), or
     unchanged;
   - every `migrations/V<n>__*.md` in the scratch clone with `<n>` greater than the
     current manifest's `migrations_applied` (0 on first adoption).

5. **Gate**, per `docs/architecture/confirmation-gates.md`, before anything is written
   or any tracker call is made:

   - evidence: current tag → target tag (or "first adoption"), counts of files
     added/changed/unchanged, and the migrations that would apply, by number and title
   - question: "Sync to `<target-tag>`?"
   - options: `confirm` / `abort`

   `confirm` proceeds; anything else stops, changing nothing.

6. **On confirmation, land it as one ordinary task** — this skill is the invoked stage,
   so opening exactly this one issue is the write its own invocation asked for
   (`AGENTS.md` §Conventions):
   - `tracker:create` an issue titled `Update template to <target-tag>`, body naming the
     tag range, the file counts from step 4, and the migrations that will apply;
   - resolve `wip/<id>-update-template-to-<target-tag>` and create it from a
     fast-forwarded `main` (`/t-work` Phase 1 step 4's exact procedure — same refusals:
     never start from a stale or diverged `main`, never work in another task's
     worktree);
   - create `docs/tasks/<bucket>/<id>-update-template-to-<target-tag>.md` from
     `docs/tasks/TEMPLATE.md`.

7. **Apply the sync** (the work, now that a task branch exists to hold it):
   - for each changed or added file whose **current** (pre-sync) content carries no
     `<!-- local -->` marker, copy the target tag's version in directly (a real copy
     from the scratch clone, preserving symlinks as symlinks — `CLAUDE.md`, `GEMINI.md`,
     `.github/copilot-instructions.md`, `.agents/skills` are recreated as the same
     symlink, never as separate file content);
   - for each changed or added file whose current content *does* carry a
     `<!-- local -->` marker (`CONSTITUTION.md`, `AGENTS.md`, and `.github/workflows/ci.yml`
     today — `docs/architecture/local-slots.md` names the current set, and this rule
     applies to whatever it names next without a further edit here), splice: take the
     target tag's file whole, but replace its `<!-- local -->…<!-- /local -->` region(s)
     with this repo's *current* content for the same region(s) before writing;
   - apply each pending migration in order (step 4's list), running its own
     Done-when check immediately after and stopping the whole update where it is if one
     fails — reported, not worked around, the same as a dirty-file refusal. **A
     migration that moves a file from no-markers to markers for the first time must
     capture that file's pre-sync content before the two bullets above touch it** — by
     the time migrations run, an unmarked file has already been overwritten wholesale by
     the first bullet, so the migration's own instructions are what is responsible for
     reading the *original* pre-sync content (`git show <the pre-update HEAD>:<path>`,
     or a copy taken before step 7 began) rather than assuming anything of the old
     content survives in the working tree by the time it runs.

8. **Write the new manifest**: target tag, the new file list with each file's
   normalized hash (`.t-workflow/scripts/check-manifest.sh --hash-file`), and `migrations_applied`
   set to the highest migration number actually applied (unchanged if none applied).

9. **Checks, commit, draft PR** — `/t-work` Phase 3, verbatim: run the checks
   (`./.t-workflow/scripts/consistency-check.sh`, plus `.t-workflow/scripts/check-manifest.sh` against the
   freshly written manifest — it must pass against what was just synced, or the sync
   itself has a bug), read the diff for scope drift, re-check
   `.t-workflow/scripts/protected-paths.sh` against what the diff actually touches, commit, push,
   open the draft PR (title `[<id>] Update template to <target-tag>`).

10. **Stop and report.** Say in ordinary language what moved (old tag → new tag, what
    changed and why, any migrations applied), what the checks returned, and name
    `/t-review <id>` as the required next step — a sync touching protected surfaces
    (nearly always) cannot skip it, matching the issue's "no fast path."

## Rules

- **Never write to the working tree before a task branch exists.** Steps 1–5 are
  entirely read-only (the scratch clone lives outside this repo); the first file this
  skill writes inside this repo's tree is in step 7, after step 6 has already put the
  work on its own branch.
- **A dirty owned file always refuses**, even if the requested sync wouldn't have
  touched that particular file. Dirt anywhere relative to the current pin means the
  manifest can no longer be trusted to describe this tree.
- **Local-slot content is never template input.** Splicing in step 7 always takes the
  slot content from *this* repo's current tree, never from the target tag — the target
  tag's own slot content is only ever the neutral placeholder
  (`docs/architecture/local-slots.md`).
- A failed migration Done-when stops the update entirely — never apply a later
  migration on top of one whose own check didn't pass.
- This skill never marks the PR ready and never merges — `/t-ship` owns that, same as
  every other task.
