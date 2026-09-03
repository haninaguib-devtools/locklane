# Forge adapter

The workflow's code-hosting backend — the thing that hosts pull requests, reviews, CI
status, and the merge button. Skills never name a vendor: they invoke the named `forge:*`
operations below, and this file maps each to a concrete command for the **active
backend**:

```
active-backend: github
```

Swapping forges means editing this file only. Plain git (branches, worktrees, commits,
fetch/push) is identical everywhere and is **not** abstracted here — skills run raw
`git` directly.

## Vocabulary

The workflow says **PR** everywhere. The tracker and the forge are independent choices —
see `docs/adapters/TRACKER.md`.

## Operations

### `forge:pr-create-draft <title> <body> [<base>]` — open a draft PR from the pushed branch

Include the tracker's auto-close phrase in the body when the tracker supports it
(`tracker:auto-close-on-merge`). `<base>` is optional: omitted, the PR targets the
repository's default branch — confirmed empirically (`/t-drive`'s own task record, #41):
a PR opened from a branch with no `--base` targets the default branch regardless of that
branch's actual git parent, never inferring one from the branch's git history. Named,
the PR targets that branch directly instead — confirmed empirically (#113: a disposable
probe PR opened with `--draft --base <non-default-branch>` landed with that exact branch
as `baseRefName`) — narrowing task #41's finding to what it actually showed: omitting
`--base` falls back to the default branch; passing it explicitly works normally, `--draft`
included. `/t-drive` (ADR-004 Decision 1) uses the `<base>` form to open each child's
draft PR directly against the initiative's integration branch, never against the trunk.

| Backend | Command |
|---|---|
| GitHub | `gh pr create --draft --title "<title>" --body "<body>"` (`<base>` omitted); `gh pr create --draft --title "<title>" --body "<body>" --base <base>` (`<base>` given) |

### `forge:pr-create <title> <body>` — open a non-draft PR directly

| Backend | Command |
|---|---|
| GitHub | `gh pr create --title "<title>" --body "<body>"` |

### `forge:pr-set-base <pr> <base>` — retarget an existing PR's base branch

Contract: moves an **already-open** PR onto a different base. Not currently called by
any skill (`/t-drive`, the only caller, switched to naming the base at creation time via
`forge:pr-create-draft`'s `<base>` argument — #113) — kept documented as a general
operation for a base decided only after the PR already exists, rather than removed
speculatively.

| Backend | Command |
|---|---|
| GitHub | `gh pr edit <pr> --base <base>` |

### `forge:pr-ready <pr>` — take the PR out of draft

| Backend | Command |
|---|---|
| GitHub | `gh pr ready <pr>` |

### `forge:pr-draft <pr>` — put the PR back into draft

| Backend | Command |
|---|---|
| GitHub | `gh pr ready <pr> --undo` |

### `forge:pr-diff <pr>` — the complete diff

| Backend | Command |
|---|---|
| GitHub | `gh pr diff <pr>` |

### `forge:pr-files <pr>` — changed paths only

Contract: this feeds the protected-surface check in `/t-ship` and `/t-review`, so it must
emit **every** changed path, **one bare repository-relative path per line and nothing
else**. Output that is not a plain path list fails in the unsafe direction: no line
matches any protected pattern, so a protected surface reads as unprotected and the
mandatory plan-and-review gate silently disappears. Verify a new backend's command by
eye before trusting it — a wrong command here does not error, it under-reports.

| Backend | Command |
|---|---|
| GitHub | `gh pr diff <pr> --name-only` |

### `forge:pr-review <pr> <body>` — post review findings as one review comment

| Backend | Command |
|---|---|
| GitHub | `gh pr review <pr> --comment --body "<body>"` |

### `forge:pr-reviews <pr>` — read the reviews and review comments on a PR, newest last

Contract: returns each review's body and the time it was submitted, so a caller can find
the **latest** one, read its `readiness:` verdict and its `## Pending human checks`
section, and tell whether commits landed after it (`forge:pr-view` carries the head
commit's time). `/t-ship`, `/t-review`, `/t-work`, and `/t-status` all depend on this.

| Backend | Command |
|---|---|
| GitHub | `gh pr view <pr> --json reviews,comments` (reviews carry `submittedAt`) |

### `forge:pr-view <pr>` — the PR's own metadata

Contract: at minimum the web URL (shown at `/t-ship`'s gate), draft-vs-ready state, head
branch, head commit and its timestamp, and mergeability against the base branch
(`/t-ship` precondition 4). Mergeability is computed asynchronously and may legitimately
return `UNKNOWN`/`checking` right after a push — callers retry a few times and then
report it as unsettled, never as clean.

| Backend | Command |
|---|---|
| GitHub | `gh pr view <pr> --json url,isDraft,headRefName,headRefOid,mergeable,mergeStateStatus,commits` |

### `forge:pr-checks <pr>` — CI status for the PR's head

Contract: distinguish "no CI configured" (acceptable, said out loud) from failing.

| Backend | Command |
|---|---|
| GitHub | `gh pr checks <pr>` |

### `forge:pr-merge <pr> <subject> <body>` — squash-merge with an explicit commit message

Contract: the squash commit's subject and body are **fully specified by the skill** —
never the forge's autogenerated default — and must end up exactly as given.

**Branch deletion is not this operation's job on GitHub.** The repo's
`delete_branch_on_merge` setting (asserted by `.t-workflow/scripts/github-bootstrap.sh`) deletes the
merged branch's remote copy without any flag here, and this command asserts no
branch-deletion flag itself — the local branch, and this checkout, are left exactly as
they were. A stale local worktree or branch is left alone permanently (ADR-005), removed
by hand only if it is ever actually in the way, never as a side effect of merging.

| Backend | Command |
|---|---|
| GitHub | `gh pr merge <pr> --squash --subject "<subject>" --body "<body>"` |

### `forge:pr-close <pr> <comment>` — close without merging, deleting the branch

**Branch deletion here is not remote-only, unlike `forge:pr-merge` above.** `gh`'s
`--delete-branch` removes the local branch as well as the remote one, and switches the
checkout to the default branch when it was on the deleted branch. Treat the branch as
*possibly already gone* after this operation: `/t-cancel`'s own cleanup must check
before deleting, and must not treat a missing branch as a failure.

| Backend | Command |
|---|---|
| GitHub | `gh pr close <pr> --delete-branch --comment "<comment>"` |

### `forge:pr-find-by-task <id>` — the PR for a task, resolved from its branch

Contract: returns every PR, any state, whose head branch matches `wip/<id>-*`. The
backend's `--head` flag does not accept a glob, and passing one returns an empty list
rather than an error — which reads as "this task has no PR" and, in `/t-cancel`, skips
closing a PR that exists. So list and filter client-side on the head branch name.

| Backend | Command |
|---|---|
| GitHub | `gh pr list --state all --limit 200 --json number,state,headRefName,url` then keep rows whose `headRefName` starts `wip/<id>-` |

Same completeness rule as `forge:pr-list`: a result sitting at the page limit is an
incomplete scan, reported as one, never as "no PR exists".

### `forge:pr-list` — enumerate PRs

Variants a skill may ask for: open PRs; merged PRs (id, title, head branch); all-state
PRs for a given head branch (an exact name, never a glob — see
`forge:pr-find-by-task`); all-state PRs repo-wide with every field `forge:pr-files`,
`forge:pr-reviews`, and `forge:pr-checks` would otherwise fetch per PR
(`.t-workflow/scripts/status-snapshot.sh` uses this last variant to correlate every local
`wip/*` branch and worktree against its PR in one call, instead of one lookup each).

Contract: the listing must be **complete** for what the caller asked. Paginate or raise
the page size until it is; a result sitting exactly at the page limit is an incomplete
scan and is reported as one, never presented as a total (ADR-001 §D6). The `--limit`
values below are starting points, not ceilings.

| Backend | Command |
|---|---|
| GitHub | `gh pr list --state open` · `gh pr list --state merged --limit 100 --json number,title,headRefName` · `gh pr list --head <branch> --state all` · `gh pr list --state all --limit 200 --json number,title,state,isDraft,headRefName,url,createdAt,mergeable,mergeStateStatus,reviews,files,statusCheckRollup` |

### `forge:pr-approval` — where a human approves, when approval rules are configured

| Backend | Behavior |
|---|---|
| GitHub | PR approval review; enforced by branch protection. |

## Bootstrap

Repo settings as code (squash-only merges, delete-branch-on-merge, protected trunk):

| Backend | Where |
|---|---|
| GitHub | `.t-workflow/scripts/github-bootstrap.sh` |
