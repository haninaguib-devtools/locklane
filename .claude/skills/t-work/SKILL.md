---
name: t-work
description: Implement a task — gate, branch, record, work within scope, checks, draft PR. Run it again on the same task to address review findings. Use when asked to work, start, pick up, continue, or fix a task.
---

# Implement a task

`/t-work <id>`; with no id, list open tasks (`gh issue list --state open`) and ask.
Discussing a task is not asking for it to be worked — confirm before editing.

## 1. Gate

Read `.t-workflow/AGENTS.md`, the issue and its `## Plan`
(`.t-workflow/scripts/issue.sh view <id>`, `issue.sh plan <id>`). Run
`.t-workflow/scripts/gate.sh work <id>`. Any `BLOCKED:` line → stop and relay it.
Otherwise the output tells you the trunk, the branch to reuse or create, whether the
tree is dirty, and the mode (`normal` or `fix`).

## 2. Branch

- `branch: none` → `git fetch origin && git checkout -b <branch> origin/<trunk>`.
- An existing branch → `git checkout <branch>`; if `branch-behind-trunk` is above 0,
  `git rebase origin/<trunk>`. A conflict stops the task: leave the rebase for the
  human, report it.
- `dirty:` changes that are not this task's → stop. Never stash or discard them.
- Never commit on the trunk.

## 3. Record

Normal mode: `.t-workflow/scripts/record.sh create <id>` writes
`docs/tasks/<id>-<slug>.md` from the issue and prints the path. Fix mode: read the
existing one. Re-planned since the record was written → note the old and new scope in
Deviations before touching anything else.

## 4. Work

The plan's Allowed paths, or the issue's Scope, are binding. A file outside them means
stop and name `/t-plan <id>`. Preserve existing behaviour unless the issue changes it.
Never weaken a check to pass. Out-of-scope defects are proposed in the report, never
fixed in passing; a pure typo fix in a file already in scope may ride along, listed in
the record. Keep the record current: decisions, deviations the human approved, dead
ends worth remembering.

**Fix mode**: address only the review's blocker and high findings; medium and low
only when the human asks by number. Note in the record what each change answers.

## 5. Checks

1. `git -c core.quotePath=false diff --name-only origin/<trunk>...HEAD | .t-workflow/scripts/docs-only.sh`
   — exit 0 means check 1 is skipped: write `check 1 skipped: documentation-only diff`
   in the record and the PR. Otherwise run the `check` command from `.t-workflow/config`
   (none configured → say so).
2. Read the whole diff (`git diff origin/<trunk>...HEAD`): scope drift, unintended
   deletions, leftover scratch. An edit here re-runs check 1.
3. Same file list through `.t-workflow/scripts/protected.sh`: protected and no plan →
   stop for `/t-plan <id>` instead of opening a PR `/t-ship` will refuse.

Report results as they are; a failure is a failure.

## 6. Commit and PR

Commit with an imperative message. `git push -u origin <branch>`. Normal mode:

```bash
gh pr create --draft --title "[<id>] <issue title>" --body-file <file>
```

Body: `Closes #<id>`, what changed and why in plain language, what remains open, and a
`## Checks run` section with one line per check:
`` - `<command>` — PASS|FAIL — commit `<sha>` `` (or the skip line above). Fix mode
pushes to the same branch and rewrites `## Checks run` for the new head
(`gh pr edit <pr> --body-file`).

## 7. Stop

Nothing chains. Say what the change does in ordinary language and what the checks
returned. Name the next command: `/t-review <id>` — required when step 5.3 found a
protected path — otherwise `/t-review <id>` for a cold read or `/t-ship <id>`.
