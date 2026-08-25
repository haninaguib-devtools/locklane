---
name: t-fix
description: Ship a change with no semantic content (typo, spelling, punctuation, formatting, broken link) as a single PR with no issue, record, or cold review. Refuses anything that changes meaning or touches a protected surface. Use for tiny fixes ("fix this typo").
---

# Meaning-free fix

One PR, one confirmation, merged in minutes. The no-issue path (ADR-001 §D2) exists so
trivial fixes never tempt anyone to bypass the pipeline — which is why
its **refusal path is its most important branch**.

## Eligibility — all must hold, or refuse and recommend `/t-open`

1. **No semantic change.** The rendered meaning before and after is identical: typo,
   spelling, punctuation, whitespace or formatting, a broken link target. If the change is
   arguable, it changes meaning. **When in doubt, this is not the path.**
2. **No protected surface** — check it, do not eyeball it:
   `bash scripts/protected-paths.sh <the paths you will touch>` (`CONSTITUTION.md` §3 in
   executable form; exit 0 = protected, 1 = none, 2 = you passed no paths). In code, only
   comments or human-facing text with zero behavior change.
3. **Size:** at most 2 files, at most ~10 changed lines.

   CI's `record` job backstops this on any `fix/` branch: it rejects a protected path,
   more than 2 files, or more than 20 changed lines. That last bound is deliberately
   looser than the ~10 above — the guidance is a judgment you make here, and CI only
   catches a task wearing this prefix to skip the record requirement. Staying inside ~10
   is still your call, not the build's.
4. **One fix per PR.** Related instances of the *same* typo may travel together within
   the size cap; unrelated nits do not (batch those via a paper-cuts issue instead).

State the eligibility outcome out loud before touching anything. A refusal names which
condition failed and hands off to `/t-open`.

## Where this runs

This path branches from `main` and returns to it, so it runs in the **primary checkout**,
never inside a task worktree where `main` is checked out elsewhere and `git checkout main`
would fail. Compare the current checkout (`git rev-parse --show-toplevel`) with the
primary one (the parent of `git rev-parse --path-format=absolute --git-common-dir`):

- **Inside a linked worktree** → stop, report the primary checkout's absolute path, and
  say to run `/t-fix` from a session rooted there.
- **In the primary checkout** → continue. Uncommitted changes that are not this fix's
  stop the skill: report them and never stash, discard, or switch over them.

## Procedure

1. Get onto a fresh branch from a **clean, current** `main` — the same freshness rule
   `/t-work` uses, because a fix branched off a stale `main` merges stale content:

   ```bash
   git fetch --prune
   git checkout main
   git merge --ff-only origin/main    # stop and report if main is ahead or diverged
   git checkout -b fix/<slug> main
   ```
2. Make the fix. **Read the diff** and confirm the eligibility claim still holds. A diff
   that no longer qualifies stops here and goes to `/t-open`.
3. Commit (imperative, no trailers), push, and open the PR — **not draft** —
   via `forge:pr-create` (resolve `forge:*` operations via `docs/adapters/FORGE.md`;
   GitHub by default). Title: what it fixes. Body: "No semantic content (ADR-001).
   Eligibility: <one line — why the meaning is unchanged, paths non-protected,
   N files / N lines>. <Before → after, if not obvious from the diff.>"

   The PR body is the record. No issue, no task record file.
4. **CI, if configured, is green** (`forge:pr-checks <pr>`). Meaning-free does not mean
   harmless — a formatting change can still fail a linter. No CI configured is
   acceptable and is said out loud, not silently skipped.
5. **Stop and ask the human to confirm**, per `docs/architecture/confirmation-gates.md`:
   describe the fix in plain prose per AGENTS.md §Communication, show the PR URL and the
   diff (it fits on a screen — show it), then the gate as the last thing in the message —

   - evidence: eligibility `<the one-line claim>` · CI `<state, or 'none configured'>` ·
     diff `<N files, +N −N>`
   - question: "Merge PR #<pr> into main?"
   - options: `confirm` (merge the fix) / `abort` (do not merge)

   This confirmation *is* this path's review (ADR-001 §D2) — there is no issue, no
   record, and no cold read behind it, so it is the only human gate the change ever
   meets. Do not merge on silence.
6. On confirmation: **before composing the merge command, re-read
   `docs/adapters/FORGE.md`'s `forge:pr-merge` row and check the exact command string you
   are about to run against it** — a command missing the active backend's
   branch-deletion flag (`--delete-branch` on GitHub) merges silently and leaves the
   branch stranded on `origin` (#39). `forge:pr-merge <pr>` — subject `<title> (#<pr>)`,
   body "No semantic content (ADR-001): <one line>." Then return the checkout to a clean,
   current `main` and remove the merged branch, exactly as `/t-ship` does:

   ```bash
   git fetch --prune
   git checkout main                        # only if not already on it
   git merge --ff-only origin/main
   git rev-parse --verify --quiet fix/<slug> && git branch -D fix/<slug>
   ```

   `forge:pr-merge` deletes the branch itself and may already have switched the checkout,
   so a branch that is gone is the normal case, not a failure. When it does survive, `-D`
   and not `-d`: the squash merge rewrote the work into a new commit, so the branch is
   never an ancestor of `main` and `-d` refuses every time. The forge reported the merge
   succeeded, so the content is on `main`.

   On `abort`: leave the PR open and say so — nothing is merged, nothing is deleted, and
   the human decides whether to close it or come back to it.

## Rules

- Never use this path because the change is *small* — only because it is *meaning-free*.
  Small-but-meaningful starts with `/t-open`.
- Never force-push; never merge without the human's confirmation in this
  conversation; never batch unrelated fixes.
- A typo in a file inside an *active task's* scope belongs to that task (ride-along,
  ADR-001), not here.
- These merges are counted by `/t-status` and sampled at retros; treat that as an audit
  you are always about to pass.
