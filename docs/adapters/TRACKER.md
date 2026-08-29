# Tracker adapter

The workflow's issue-tracking backend. Skills never name a vendor: they invoke the named
`tracker:*` operations below, and this file maps each operation to a concrete command for
the **active backend**. The active backend is declared once, here:

```
active-backend: github
```

Swapping trackers means editing this file only — no skill changes. A backend must satisfy
the **contract** for each operation; the per-backend command table is that contract's
implementation.

## Vocabulary

The workflow says **issue** everywhere. The **task ID** is the tracker's native identifier
— the GitHub issue number; it appears in branch names (`wip/<id>-<slug>`) and record
filenames.

## Classification labels

`/t-open` tags every task issue (never a tracking issue) with exactly one of these, so
issues can be grouped/filtered by kind later. Reuses GitHub's own default label set
already present in this repo rather than inventing a parallel taxonomy; ensured to exist
via `tracker:ensure-labels` (backed by `.t-workflow/scripts/github-bootstrap.sh`).

| Label | Meaning |
|---|---|
| `bug` | Something isn't working |
| `enhancement` | New feature or request |
| `documentation` | Improvements or additions to documentation |
| `question` | Further information is requested |

Another backend adopts its own equivalent four.

## Workflow-reserved labels

Some labels are read or written by the skills for machine logic, not by a human's
project-specific vocabulary. `tracker:list-labels` results must exclude every one of
these from the pool `/t-open` considers for its optional auto-apply pass (see that
operation below) — they stay governed only by their existing fixed mechanism
(`tracker:ensure-labels`, step 4 of `/t-open`, `/t-cancel`'s use of `cancelled`).

- The four classification labels above: `bug`, `enhancement`, `documentation`,
  `question`.
- `initiative` — marks a tracking issue (`tracker:list-initiatives`).
- `cancelled` — marks a closed-as-not-planned issue (`tracker:list-cancelled`,
  `/t-cancel`).

Keep this list in sync with any future addition to the reserved set — a new
machine-read label belongs here the same day it starts being written by a skill.

## Operations

Each operation states its contract, then the command per backend. Where a backend needs
extra steps, they are listed — a skill treats the whole entry as one operation.

**The relation operations below** (`set-parent`, `remove-parent`, `list-children`,
`add-blocker`, `remove-blocker`, `list-blockers`, `list-blocking`), and the `parent` /
`blockedBy` / `blocking` / `subIssues` / `subIssuesSummary` fields the read operations
return, need gh CLI ≥2.94.0 (2026-06-10) — the release that added native sub-issue and
issue-dependency support to `gh issue`. An older gh rejects these flags and JSON fields
outright (`Unknown JSON field`) rather than degrading quietly; confirm `gh --version`
before relying on them (ADR-003).

### `tracker:view <id>` — full issue: number, title, body, state, labels, parent, subIssuesSummary

| Backend | Command |
|---|---|
| GitHub | `gh issue view <id> --json number,title,body,state,labels,parent,subIssuesSummary` |

### `tracker:list-open` — ALL open issues with id, title, labels, body, and blockedBy

Contract: the scan must be **complete** (paginate or raise the page size until it is; a
truncated list must be reported as an incomplete scan, never as "none found") and each
row carries its labels and `blockedBy`, so callers can filter initiatives and check
blocked state without extra per-issue calls.

| Backend | Command |
|---|---|
| GitHub | `gh issue list --state open --limit 1000 --json number,title,body,labels,blockedBy` (default limit is 30 — always pass it) |

### `tracker:list-initiatives` — open issues labeled `initiative`, with `subIssuesSummary`

| Backend | Command |
|---|---|
| GitHub | `gh issue list --label initiative --state open --json number,title,subIssuesSummary` |

### `tracker:list-cancelled` — closed issues labeled `cancelled`, id + title

Contract: complete, on the same terms as `tracker:list-open` — a result at the page
limit is an incomplete scan, never a count (ADR-001 §D6). Raise the limit or paginate.

| Backend | Command |
|---|---|
| GitHub | `gh issue list --state closed --label cancelled --limit 100 --json number,title` |

### `tracker:list-labels` — every label the tracker knows, with name and description

Contract: return the full label set, name plus description (a discovered label with no
description is still returned — its name alone may be unambiguous). Callers filter out
the workflow-reserved set (above) before considering any result for auto-apply.

| Backend | Command |
|---|---|
| GitHub | `gh label list --json name,description` |

### `tracker:create <title> <body>` — create an issue, return its ID

| Backend | Command |
|---|---|
| GitHub | `gh issue create --title "<title>" --body "<body>"` |

### `tracker:edit-body <id>` — replace/append the issue body, preserving what is there

Contract: read the current body first; never clobber it.

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --body "<current + appended>"` |

### `tracker:ensure-labels <label>…` — idempotently create the workflow's labels

The label set and colors live in `.t-workflow/scripts/github-bootstrap.sh` (write the equivalent
bootstrap script when adopting another backend).

| Backend | Command |
|---|---|
| GitHub | `gh label list`; `gh label create <name> --color <hex> --description "…" --force` |

### `tracker:label <id> <label>` — add a label to an issue

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --add-label <label>` |

### `tracker:set-parent <child-id> <parent-id>` — link an issue as another's sub-issue

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <child-id> --parent <parent-id>` |

### `tracker:remove-parent <id>` — drop an issue's parent link, promoting it to standalone

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --remove-parent` |

### `tracker:list-children <id>` — an issue's sub-issues, each with number, title, state

| Backend | Command |
|---|---|
| GitHub | `gh issue view <id> --json subIssues` |

### `tracker:add-blocker <id> <blocker-id>` — mark `<id>` as blocked by `<blocker-id>`

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --add-blocked-by <blocker-id>` |

### `tracker:remove-blocker <id> <blocker-id>` — drop one blocked-by edge from `<id>`

| Backend | Command |
|---|---|
| GitHub | `gh issue edit <id> --remove-blocked-by <blocker-id>` |

### `tracker:list-blockers <id>` — issues blocking `<id>`, each with number, title, state

Contract: state is required per blocker — the blocker gate (`/t-work`) must tell a
closed-as-completed blocker from one closed-as-cancelled (abandoned, not satisfied).

| Backend | Command |
|---|---|
| GitHub | `gh issue view <id> --json blockedBy` |

### `tracker:list-blocking <id>` — issues `<id>` blocks (its dependents), same fields

Contract: this is the whole of `/t-cancel`'s dependent sweep for one issue — no
open-issue body scan needed, unlike the retired `Blocked-by:` marker convention.

| Backend | Command |
|---|---|
| GitHub | `gh issue view <id> --json blocking` |

### `tracker:comment <id> <text>` — comment on an issue

| Backend | Command |
|---|---|
| GitHub | `gh issue comment <id> --body "<text>"` |

### `tracker:close <id> <reason> <comment>` — close as not-planned, with the reason

Contract: the close must be distinguishable from a completion, and the comment carries
the durable account. **This is the cancellation close** — never use it for a task that
shipped (that is `tracker:close-done`).

| Backend | Command |
|---|---|
| GitHub | `gh issue close <id> --reason "not planned" --comment "<comment>"` |

### `tracker:close-done <id>` — close as completed

Contract: the close must read as a completion, never as not-planned — `/t-work`'s
blocker gate treats a not-planned close as an abandoned blocker.

| Backend | Command |
|---|---|
| GitHub | `gh issue close <id> --reason completed` |

### `tracker:auto-close-on-merge <id>` — does merging a linked PR close the issue?

| Backend | Behavior |
|---|---|
| GitHub | Yes — `Closes #<id>` in the PR body closes the issue on merge. |

## Bootstrap

`.t-workflow/scripts/github-bootstrap.sh` is the GitHub implementation of tracker + forge bootstrap
(labels, merge mechanics, branch protection). Issue templates are part of the tracker
surface too: GitHub's live in `.github/ISSUE_TEMPLATE/` (spec:
`docs/architecture/issue-templates.md`). Adopting another backend means writing its own
bootstrap script and issue-template equivalent, and switching `active-backend` above.
