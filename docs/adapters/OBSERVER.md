# Observer adapter contract

**Status:** binding convention. Implements ADR-010 §D2/§D7 for the "external observer"
party (issue #143): the read-only surface a system such as Proposarium watches to
follow t-workflow's progress, without ever scraping human prose and without ever
becoming a second place any of that progress is decided.

## Why this exists, in plain terms

A system outside this repository — a research tool, a proposal tracker, a dashboard —
sometimes wants to know where a piece of work stands: has it started, is it stuck, is
it waiting on a person, is it done. Today the honest answer lives scattered across an
issue, a PR, a review comment, a task record, and a handful of scripts that combine
them. An outside system has no reasonable way to reconstruct that by reading English
prose, and it must never be given a way to *change* any of it. This document names
exactly what such a system may read, in a shape stable enough to build against, and
draws the line at read-only, once, so nobody downstream has to re-derive where that
line sits.

## What this is not

This is a **contract**, not a service. t-workflow runs no webhook relay, no event bus,
and no long-lived process that pushes anything to an observer — an interested party
reads GitHub's own issues, PRs, comments, and commits (directly, or via its webhooks)
and this document is the map from what it sees there to what it means. Building an
actual observer, subscribing it to Proposarium or anything else, and letting it send
anything back into t-workflow are all explicitly out of scope (issue #143 Non-goals).

This is also **evidence, never authority**, the same posture every other party in
ADR-010 already keeps to: an observer's own read of t-workflow's state is never itself
binding on anything, and nothing here gives it a channel to write back. `## D2 —
Responsibilities`, below, states the boundary the same way ADR-010's own table does.

## D1 — Identifiers: what correlates what

An observer needs to answer one question reliably, for anything it is looking at: *which
initiative, task, PR, origin, and commit is this?* Two sources answer it, and this
document is explicit about which:

### Already-native — no marker needed

| To get | From | How |
|---|---|---|
| A task's id | A branch name | `wip/<id>-<slug>` — split at the first hyphen (`AGENTS.md` §Conventions; unambiguous, since the id is always the leading numeric run). |
| The task a PR belongs to | The PR body | The tracker's auto-close phrase (`Closes #<id>` on GitHub), present in every draft PR body since before this initiative (`docs/adapters/FORGE.md` `forge:pr-create-draft`) — a fixed, mandated phrase, not prose to interpret. |
| An initiative a PR's *branch* belongs to, when that branch is itself an integration branch | The PR's base branch name | `wip/<initiative-id>-integration` (ADR-004) — an initiative child's PR during a driven run targets this directly. |
| The revision a piece of state speaks for | The commit sha itself | Whatever field names it — `headRefOid` on a PR, the sha in a comment's fenced evidence block (`docs/adapters/PREVIEW.md`), the record's own `revision:` field (`docs/architecture/verification.md`) — see §D3, below. |

### Not reliably native — the correlation marker closes the gap

Two facts are genuinely not recoverable from a PR or commit payload alone, without a
second round-trip back to the issue: **which initiative a task is a child of**, and
**what origin, if any, this work carries** (ADR-010 §D1). An observer watching only PR
or commit events — never opening the issue — has no native field for either. This
document adds exactly one thing to close that gap: a single-line, machine-only marker
`/t-open` appends to a new issue's body at creation time (`.claude/skills/t-open/
SKILL.md`), invisible in GitHub's rendered view because it is an HTML comment, so
Done-when's "human-facing issue and PR content remains readable without interpreting
the markers" holds by construction rather than by convention.

**Grammar** — exactly one line, prefix through suffix:

```
<!-- t-workflow:v1 (task=<id>|initiative=<id>) [parent=<initiative-id>]
     [origin-system="<name>" origin-url=<url>] -->
```

(shown wrapped for readability; it is written and read as one physical line). A value
with no spaces may be given bare; one with spaces must be double-quoted.

- **Exactly one of `task=`/`initiative=`.** An issue is a task or a tracking issue,
  never both (`CONSTITUTION.md` §1.2, ADR-003) — this marker never adds a case the
  hierarchy itself doesn't have.
- **`parent=<initiative-id>`**, optional, only alongside `task=` — the same
  `parent`/`subIssuesSummary` relationship `tracker:view` already returns natively;
  this is a redundant, forge-neutral copy of it for a consumer that never calls
  `tracker:view` at all.
- **`origin-system=`/`origin-url=`**, optional, both or neither — the exact same rule
  `docs/architecture/external-origin.md` already applies to the human-facing `##
  Origin` section this marker echoes verbatim: an origin with only one field is
  incomplete and is never written at all (§D1, that document).
- **No other key.** The marker is versioned (`t-workflow:v1`) precisely so a future,
  incompatible shape gets a new prefix (`t-workflow:v2`) rather than silently changing
  what `v1` consumers already parse.

**Where it appears.** Written once, by `/t-open`, into the body of the issue it just
created (task or initiative alike) — never edited afterward by any skill, the same
"written once at open time" rule `external-origin.md` already applies to `##
Origin` itself. No PR-body marker is added: a PR's own `Closes #<id>` phrase already
resolves to the issue that carries this one (§Already-native, above), so duplicating it
onto the PR would be a second, driftable copy of the same fact rather than a new one.

**Contains identifiers and derived correlation only — never scope, acceptance
criteria, or a review decision** (Done-when's explicit bar). Nothing in this grammar
can ever hold a Goal, a Done-when criterion, an Allowed path, or a `readiness:` verdict
— those remain exactly where ADR-010 §D3 already says they live: the issue body, the
plan, the review, and the merged diff.

**Validated, never gated, by `.t-workflow/scripts/check-observer-marker.sh`** — the
same "shape-only, wired into no gate" posture `docs/adapters/PREVIEW.md`'s own
`check-preview-evidence.sh` already established: reading or emitting this marker is
convenience for an external party, never required by `/t-ship`, `/t-review`, or CI.

## D2 — Responsibilities

| Party | May | May never |
|---|---|---|
| t-workflow (skills/scripts) | Write the correlation marker at issue-creation time; leave every native GitHub field (branch names, `Closes #<id>`, labels, `parent`) exactly as reliable as it already is | Treat an observer's read, or anything an observer reports back, as authoritative for scope, review completion, or merge (ADR-010 §D2) |
| External observer | Read issues, PRs, comments, commits, and this marker, by whatever means it has (webhooks, polling `gh`/the GitHub API, cloning the repo) | Open, edit, label, or close an issue; approve or complete a review; merge; write anything back into t-workflow at all — this contract defines no write operation, in either direction (issue #143 Non-goals) |
| Human maintainer | Decide whether an observer exists at all for a given repository, and what it is used for | — the same authority every other ADR-010 boundary is drawn against |

## D3 — Revisions and superseded commits

Every fact this contract exposes that can go stale names the exact commit it speaks
for — the same discipline `docs/adapters/PREVIEW.md` and
`docs/architecture/verification.md` already apply to their own evidence:

- A PR's `headRefOid` (or, from a webhook, the `pull_request.head.sha` a `synchronize`
  event carries) is the current revision. Any commit sha an observer holds that is not
  equal to the current `headRefOid` is **superseded** — evidence, a review, or a
  verification tied to it describes a commit that is no longer what a human is looking
  at.
- **Staleness reuses the one formula already defined**, never a second one: a required
  human-verification entry is stale exactly per `docs/architecture/verification.md`'s
  own rule (recorded `revision` differs from the current head, unless a declared
  `scope:` proves the diff between them is irrelevant); preview evidence is tied to an
  exact `commit` per `docs/adapters/PREVIEW.md`, and evidence for an old commit never
  describes the new one.
- **An observer detects supersession the same way `/t-ship` and `/t-drive` already
  do**: compare the commit sha a piece of evidence names against the PR's current head.
  No new algorithm is introduced here — this section only says explicitly that the
  same comparison an observer can make from data it already has (a webhook's own head
  sha, or one `forge:pr-view` call) is sufficient, without re-implementing
  `derive-task-state.sh` or `parse-task-record.sh` itself.

## D4 — Events and lifecycle state (forge-neutral, then GitHub-concrete)

An "event," in this contract, is never a payload t-workflow emits — it is a **native
forge event an observer already receives**, given a name and a meaning here so an
observer does not have to reverse-engineer the pipeline from raw payloads. Naming them
once, in one table, is what keeps this document from drifting into a second vocabulary
alongside `docs/architecture/collaboration-state.md`'s own — every in-flight state
below is exactly one of that document's nine; this table adds only the lifecycle
bookends that document's own scope excludes (before a PR exists, and after one is
merged), and cites the rest rather than restating it.

### Bookend events — before and after `docs/architecture/collaboration-state.md`'s table

| Forge-neutral moment | Meaning | GitHub-concrete signal |
|---|---|---|
| **opened** | A task or initiative issue now exists. | `issues` event, `action: opened`; the issue's own labels distinguish a tracking issue (`initiative`) from a task (one of the four classification labels, `docs/adapters/TRACKER.md`). |
| **planned** | The issue now carries a `## Plan` section (required before a protected surface, optional otherwise). | `issues` event, `action: edited`, whose new body now contains a `## Plan` heading — an observer that wants this reliably should diff old/new body text for that heading, since GitHub's own payload does not name which section changed. |
| **in-flight** | A draft PR exists; the task's state is now exactly one of `docs/architecture/collaboration-state.md`'s nine (`blocked` … `awaiting-ship`) — **read that document**, not this one, for what each means and their precedence. | `pull_request` events (`opened`, `synchronize`, `review_requested`), `pull_request_review` (`submitted`), `check_suite`/`check_run`, `issue_comment` (for a preview-evidence comment, `docs/adapters/PREVIEW.md`) — `docs/architecture/collaboration-state.md`'s own `derive-task-state.sh` is the authoritative re-derivation an observer can also run itself, given the same read-only `tracker:*`/`forge:*` calls `status-snapshot.sh` already makes. |
| **merged-into-integration** | A driven initiative child's PR merged into `wip/<initiative>-integration`, not the trunk (ADR-004). The child's own issue stays open (ADR-009) — an observer that reads "closed" as "done" here is wrong; watch the PR's `merged`/`base.ref`, not the issue's `state`. | `pull_request` event, `action: closed`, `merged: true`, `base.ref` matching `wip/<n>-integration`. |
| **shipped** | The initiative's own aggregate PR reached the trunk (ADR-004); every included child's issue closes at that point (ADR-009), and `Task: #<id>` lines in the merged squash commit name every included child (`CONSTITUTION.md` §1.4). | `pull_request` event, `action: closed`, `merged: true`, `base.ref` equal to the trunk; the commit's own message is the authoritative account of what was included, per `CONSTITUTION.md` §1.3. |
| **cancelled** | A task or initiative was abandoned rather than completed (`/t-cancel`, ADR-001 §D3). | `issues` event, `action: closed`, with the `cancelled` label already applied (`docs/adapters/TRACKER.md` §Workflow-reserved labels) — never inferred from a closed issue alone, since a shipped task's issue also closes. |

### In-flight states — read `docs/architecture/collaboration-state.md`, cited not restated

Once a draft PR exists, the exact state name, its meaning, and the precedence when more
than one condition is true all live in `docs/architecture/collaboration-state.md` — an
observer implementing this contract reads that document's table directly rather than a
second copy of it here: `blocked`, `checks-failing`, `awaiting-review`,
`awaiting-renewed-review`, `changes-requested`, `processing-feedback`,
`awaiting-preview`, `awaiting-external-verification`, `verified-ready-for-ship` /
`awaiting-ship`. `docs/architecture/feedback-pass.md`'s own classification
(`clarification` / `defect` / `in-scope adjustment` / `proposed scope expansion`) and
`docs/architecture/verification.md`'s own states (`pending` / `verified` / `rejected`
/ `risk-accepted`) are likewise cited, never re-minted, wherever this contract needs
to name them.

## D5 — Delivery guarantees, retries, duplicates, ordering, outages

t-workflow relies entirely on the forge's own delivery (GitHub webhooks, or an
observer's own polling of the GitHub API) — it runs no delivery mechanism of its own,
so this section states the posture that follows from that, rather than a guarantee
t-workflow itself could make:

- **At-least-once, not exactly-once.** A webhook, GitHub's own included, can redeliver
  an event; an observer must treat every event as **idempotent** — applying the same
  one twice must never change the derived answer, because the answer is always
  *re-derived from current state*, never accumulated from a stream of deltas.
- **Ordering is not guaranteed.** Two events for the same PR can arrive out of order (a
  `synchronize` after a `pull_request_review`, say). The fix is the same as for
  duplicates: an event is a **hint to re-fetch and recompute**, keyed on the entity it
  names (a task id, a PR number), never a delta applied in sequence. `docs/architecture/
  collaboration-state.md`'s own precedence table already resolves "more than one thing
  is true right now" for exactly this reason — an observer that re-derives from a fresh
  read never needs its own ordering logic on top.
- **A missed event, or a temporary observer outage, is harmless by the same
  mechanism.** Nothing here is a log an observer must replay gap-free: on
  reconnecting, or on any cadence it chooses, an observer re-fetches current state for
  whatever it is watching (the same `tracker:*`/`forge:*` reads `status-snapshot.sh`
  already performs) and gets the right answer regardless of what it missed while it
  was down. This is ADR-010 §D7's fail-toward-absent posture applied to delivery
  itself: an absent or late event is never treated as a false "nothing changed."
- **Nothing here is authoritative until git says so** (`CONSTITUTION.md` §1.3). An
  event, of any kind, delivered any number of times, in any order, is never itself the
  record of what happened — the merged commit is. This contract exists to make
  *watching* cheap, never to relocate authority.

## Revisit triggers

- A second forge backend needs an equivalent marker or event mapping this document's
  GitHub-concrete columns don't already generalize — resolved by adding that backend's
  own column, the same way `docs/adapters/TRACKER.md`/`FORGE.md` add a backend by
  adding a row, never by a provider-specific special case in a skill.
- `docs/architecture/collaboration-state.md`'s own precedence table changes — this
  document's §D4 citation follows without needing its own edit, since it names states
  by reference rather than by copy.
- A real observer integration finds the correlation marker's grammar cannot express a
  fact it needs — resolved by amending this document's §D1 grammar (and bumping to
  `t-workflow:v2` if the change is incompatible), not by a second, provider-specific
  marker convention.
- ADR-010 itself is revisited in a way that changes what an external observer may or
  may never do (ADR-010 §D2) — this document's own boundary follows.
