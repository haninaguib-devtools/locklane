# Tracker adapter

The workflow's issue-tracking backend. Skills never name a vendor: they invoke the named
`tracker:*` operations below, and this file maps each operation to a concrete command for
the **active backend**. The active backend is declared once, here:

```
active-backend: github
```

Swapping trackers (GitHub Issues → Jira, GitLab Issues, …) means editing this file only —
no skill changes. Every backend must satisfy the **contract** for each operation; the
per-backend command tables are implementations of that contract.

## Vocabulary

The workflow says **issue** everywhere. Map it mentally per backend: a GitHub/GitLab
issue, a Jira ticket. The **task ID** is the tracker's native identifier (GitHub/GitLab
issue number, Jira key such as `PROJ-152`); it appears in branch names
(`wip/<id>-<slug>`) and record filenames, so it must be filename-safe — lowercase a Jira
key for those uses.

## Operations

Each operation states its contract, then the command per backend. Where a backend needs
extra steps, they are listed — a skill treats the whole entry as one operation.

### `tracker:view <id>` — full issue: title, body, state, labels

| Backend | Command |
|---|---|
| GitHub | `gh issue view <id>` (state only: `gh issue view <id> --json state,stateReason`) |
| GitLab | `glab issue view <id>` |
| Jira | `jira issue view <KEY>` (state: the Status field; "cancelled" is a resolution or label, see `tracker:close`) |

### `tracker:list-open` — ALL open issues with id, title, labels, and full body

Contract: the scan must be **complete** (paginate or raise the page size until it is; a
truncated list must be reported as an incomplete scan, never as "none found") and each
row carries its labels, so callers can filter initiatives without extra calls.

| Backend | Command |
|---|---|
| GitHub | `gh issue list --state open --limit 1000 --json number,title,body,labels` (default limit is 30 — always pass it) |
| GitLab | `glab issue list --per-page 100 --page <n>` (loop pages) or `glab api "projects/:id/issues?state=opened&per_page=100"` |
| Jira | `jira issue list --status "~Done" --plain --columns key,summary` then `jira issue view` per hit, or the search API with `maxResults` paging |

### `tracker:list-initiatives` — open issues labeled `initiative`

| Backend | Command |
|---|---|
| GitHub | `gh issue list --label initiative --state open` |
| GitLab | `glab issue list --label initiative` |
| Jira | `jira issue list --label initiative --status "~Done"` (or model initiatives as Epics and list Epics) |

### `tracker:list-cancelled` — closed issues labeled `cancelled`, id + title

Contract: complete, on the same terms as `tracker:list-open` — a result at the page
limit is an incomplete scan, never a count (ADR-001 §D6). Raise the limit or paginate.

| Backend | Command |
|---|---|
| GitHub | `gh issue list --state closed --label cancelled --limit 100 --json number,title` |
| GitLab | `glab issue list --closed --label cancelled` |
| Jira | `jira issue list --status Done --label cancelled` (or resolution = "Won't Do") |

### `tracker:create <title> <body>` — create an issue, return its ID

| Backend | Command |
|---|---|
| GitHub | `gh issue create --title "<title>" --body "<body>"` |
| GitLab | `glab issue create --title "<title>" --description "<body>"` |
| Jira | `jira issue create --type Task --summary "<title>" --body "<body>" --no-input` |

### `tracker:edit-body <id>` — replace/append the issue body, preserving what is there

Contract: read the current body first; never clobber it.

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --body "<current + appended>"` |
| GitLab | `glab issue update <id> --description "<current + appended>"` |
| Jira | `jira issue edit <KEY> --body "<current + appended>" --no-input` |

### `tracker:ensure-labels <label>…` — idempotently create the workflow's labels

The label set and colors live in `scripts/github-bootstrap.sh` (write the equivalent
bootstrap script when adopting another backend).

| Backend | Command |
|---|---|
| GitHub | `gh label list`; `gh label create <name> --color <hex> --description "…" --force` |
| GitLab | `glab label create --name <name> --color "#<hex>" --description "…"` (already-exists errors are fine) |
| Jira | Labels are free-form — nothing to create. Skip. |

### `tracker:label <id> <label>` — add a label to an issue

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --add-label <label>` |
| GitLab | `glab issue update <id> --label <label>` |
| Jira | `jira issue edit <KEY> --label <label> --no-input` |

### `tracker:comment <id> <text>` — comment on an issue

| Backend | Command |
|---|---|
| GitHub | `gh issue comment <id> --body "<text>"` |
| GitLab | `glab issue note <id> --message "<text>"` |
| Jira | `jira issue comment add <KEY> "<text>"` |

### `tracker:close <id> <reason> <comment>` — close as not-planned, with the reason

Contract: the close must be distinguishable from a completion, and the comment carries
the durable account. **This is the cancellation close** — never use it for a task that
shipped (that is `tracker:close-done`).

| Backend | Command |
|---|---|
| GitHub | `gh issue close <id> --reason "not planned" --comment "<comment>"` |
| GitLab | `glab issue note <id> --message "<comment>"` then `glab issue close <id>` (the `cancelled` label carries the distinction) |
| Jira | `jira issue comment add <KEY> "<comment>"` then transition with resolution "Won't Do": `jira issue move <KEY> Done` (configure the resolution in the workflow) |

### `tracker:close-done <id>` — close as completed

Contract: the close must read as a completion, never as not-planned — `/t-work`'s
blocker gate treats a not-planned close as an abandoned blocker.

| Backend | Command |
|---|---|
| GitHub | `gh issue close <id> --reason completed` |
| GitLab | `glab issue close <id>` |
| Jira | `jira issue move <KEY> Done` (resolution "Done") |

### `tracker:auto-close-on-merge <id>` — does merging a linked PR close the issue?

| Backend | Behavior |
|---|---|
| GitHub | Yes — `Closes #<id>` in the PR body closes the issue on merge. |
| GitLab | Yes — `Closes #<id>` in the MR description (same-project issues). |
| Jira | **No.** Smart-commit automation may exist but is not assumed: after `forge:pr-merge`, `/t-ship` must transition the ticket to Done explicitly. |

## Bootstrap

`scripts/github-bootstrap.sh` is the GitHub implementation of tracker + forge bootstrap
(labels, merge mechanics, branch protection). Issue templates are part of the tracker
surface too: GitHub's live in `.github/ISSUE_TEMPLATE/` (spec:
`docs/architecture/issue-templates.md`); another backend supplies its own equivalent. Adopting another backend means writing its
sibling (`scripts/gitlab-bootstrap.sh`, a Jira project-config note) and switching
`active-backend` above.
