---
name: l-release
description: Cut a release with one command — gates on the version, drives the release-notes task to a merge-and-dispatch confirmation, publishes the release, then drives a follow-up snapshot-bump task. Use when the human asks to cut, release, or ship a version.
---

# Cut a release: /l-release <version> [<next-version>]

`<version>` is the bare version to cut (e.g. `0.1.0`, for tag `v0.1.0`). The optional
`<next-version>` overrides the default patch-increase snapshot bump (`0.1.0` →
`0.1.1-SNAPSHOT`); give it a bare version too (`/l-release 0.1.0 0.2.0` bumps to
`0.2.0-SNAPSHOT`).

This is locklane's own second explicitly-invoked exception to
[ADR-001](../../../docs/adr/001-phase0-delivery-workflow.md) D1's "nothing
auto-chains" — [ADR-106](../../../docs/adr/106-l-release-single-command-release.md)
records why, alongside `/t-drive`'s own. **One `/l-release <version>` invocation is the
human's ask covering every write the two `/t-drive` calls below make for their own
chained stages** (`AGENTS.md` §Conventions), exactly as a bare `/t-drive <id>`
invocation covers its own chain. This skill composes `/t-open` and `/t-drive`; it never
reimplements either, and it never touches `.github/workflows/release.yml` or
`scripts/generate-release-notes.sh` beyond running them exactly as
`docs/architecture/releasing.md` already documents.

## Procedure

1. **Gate, before any write.** Read `pom.xml`'s `<revision>` and confirm it equals
   `<version>-SNAPSHOT` exactly. Check `v<version>` does not already exist as a tag
   (`git tag -l v<version>`) or a release (`gh release view v<version>`). Either check
   failing → **stop loudly, create nothing**, and say exactly what to fix:
   - revision mismatch → name the actual `<revision>` value and what `/l-release`
     expected.
   - tag/release already exists → name it; a released version is immutable
     (`docs/architecture/releasing.md` § Immutability) — bump `<revision>` and re-run
     `/l-release` with the new version instead.

2. **Open the release-notes task.** `/t-open`, producing a task issue whose body reads
   correctly on its own even if someone runs it by hand later:

   ```markdown
   ## Goal
   Cut release v<version>: generate its CHANGELOG.md section and land it on main.

   ## Done when
   - `./scripts/generate-release-notes.sh generate --version <version>` has been run
     and `CHANGELOG.md` has a `## v<version>` section.
   - The section is reviewed and merged to `main`.

   ## Scope
   `CHANGELOG.md`

   ## Non-goals
   - Does not dispatch the Release workflow — that happens once this PR merges
     (`/l-release`, after this task's merge gate).
   ```

3. **Drive the notes task.** `/t-drive <notes-id>` (solo mode, ADR-006): plan-if-needed,
   work, review-if-needed, chained into `/t-ship`'s merge-confirmation gate exactly as
   ADR-006 D3 describes.

   **At that gate, extend `/t-ship`'s own evidence and question — do not replace
   them** — to also name the dispatch this confirmation authorizes (ADR-106 D1 step 3,
   the same "fold the enabling action into the one gate that authorizes it" shape
   `/t-ship` Procedure step 3/5 already uses for its own branch-protection flip):

   - evidence: everything `/t-ship`'s own gate already states, plus: `release` `v<version>
     will be dispatched and published immediately after this merge`.
   - question: `"Merge PR #<pr> into main, and publish release v<version>?"`
   - options: unchanged — `confirm` / `abort`.

   **`abort` stops `/l-release` entirely here** — no dispatch, no bump task opened.
   `confirm` merges the notes PR (`/t-ship`'s own Procedure) and authorizes step 4.

4. **Dispatch and verify, immediately after the merge.**
   ```bash
   gh workflow run release.yml
   ```
   Watch the run to conclusion (`gh run watch <run-id>`, or poll `gh run list --workflow
   release.yml --limit 1` until it concludes). Then confirm the release exists and
   carries the right body:
   ```bash
   gh release view v<version> --json body,tagName
   ```
   its body equal to `./scripts/generate-release-notes.sh extract --version <version>`
   run against the now-merged `main`. **A red run, a run that never starts, or a
   published body that does not match → stop and report exactly what's wrong** — never
   proceed to step 5 on a dispatch that cannot be confirmed to have succeeded.

5. **Open the snapshot-bump task.** `/t-open`, self-sufficient the same way:

   ```markdown
   ## Goal
   Bump `<revision>` to `<bump-version>-SNAPSHOT` for the next development cycle, now
   that v<version> is released.

   ## Done when
   `pom.xml`'s `<revision>` reads `<bump-version>-SNAPSHOT`.

   ## Scope
   `pom.xml`

   ## Non-goals
   - Does not itself cut or dispatch anything — v<version> already shipped in step 4.
   ```

   `<bump-version>` is `<next-version>` when given; otherwise the default patch
   increase of `<version>` (`0.1.0` → `0.1.1`). State the chosen value plainly in the
   issue and in this run's own report — the default is a suggestion, not a silent
   decision (`docs/architecture/releasing.md` § Non-goals).

6. **Drive the bump task.** `/t-drive <bump-id>` (solo mode), chained into its own
   ordinary `/t-ship` merge-confirmation gate — unmodified, nothing folded in. This
   gate is the run's second and last stop.

7. **Stop and report.** Both tasks' numbers and PRs, the published release URL, the
   final `<revision>` value, and — if either gate was answered `abort` — exactly where
   the run stopped and what state that leaves things in (notes merged and released but
   no bump task opened; or nothing happened at all, if the first gate aborted).

## Rules

- Never dispatch `release.yml` before step 3's gate is confirmed — the confirmation is
  what authorizes it, not the notes PR merging by itself.
- Never open the bump task before step 4 confirms the release actually published —
  a bump task implies the version it names is live.
- Never choose `<bump-version>` silently past the stated patch default; a human
  overrides it by passing `<next-version>`, or by editing the bump task's PR before its
  own gate.
- Never touch `.github/workflows/release.yml` or `scripts/generate-release-notes.sh` —
  run them exactly as documented; a defect in either is its own issue, not a drive-by
  fix here.
