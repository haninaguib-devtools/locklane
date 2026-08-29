# Issue templates — design

**Status:** binding.

Specification for the issue templates that give hand-opened issues the same shape
`/t-open` produces. These are the **GitHub tracker's** implementation
(`.github/ISSUE_TEMPLATE/`, GitHub issue forms); another tracker backend supplies its
own equivalent mechanism (GitLab description templates, Jira issue-type fields) mirroring
the same field set — see `docs/adapters/TRACKER.md`.

## Source of truth

`.claude/skills/t-open/SKILL.md` defines the canonical issue shape. The templates
**mirror** it and must never restate its rules in a way that can silently diverge:
prompts point at the rule's home (CONSTITUTION §3) instead of copying rule text. When
t-open's body template changes, the templates are updated in the same change or a
follow-up task opened immediately.

## Format: YAML issue forms

Both templates are GitHub **issue forms** (`.yml`), not classic markdown templates.

Rationale: the problem being solved is that hand-opened issues skip structure entirely.
Markdown templates are prefill-only — every section is deletable and nothing is
enforced. Forms mark fields `required`, so the chooser itself refuses an issue without
a Goal or Done-when. The known trade-off: a malformed form file is *silently omitted*
from the chooser (no CI signal), so any change to these files carries a YAML-validity
check at implementation time, and a post-merge look at the chooser is a named human
check.

Submitted forms render each field as a `### <label>` heading followed by the value.
This differs from t-open's `## <heading>` levels; for the **prose** sections the section
*names and order* are the contract, not the heading depth.

**Parent and blocked-by are not form fields.** They were body-text markers
(`Part of: #n`, `Blocked-by: #n`) before ADR-003; the tracker now models both natively —
a sub-issue's `parent` link, an issue's `blockedBy`/`blocking` dependencies — and a
static issue form has no way to write to that relation graph at submission time, only to
the body. A hand-opened issue therefore gets these set afterward, in the tracker's own
UI (GitHub's sidebar: "Parent issue", "Blocked by"/"Blocking" under linked issues),
exactly as an issue opened by `/t-open` gets them via `tracker:set-parent` /
`tracker:add-blocker` right after creation. `/t-work`'s blocker gate, `/t-status`'s
blocked state, and `/t-cancel`'s dependent, parent, and children sweeps all read the
native fields — there is no body text for any of them to parse any more.

## Files

```
.github/ISSUE_TEMPLATE/task.yml
.github/ISSUE_TEMPLATE/initiative.yml
.github/ISSUE_TEMPLATE/config.yml
```

## config.yml (literal contents)

```yaml
blank_issues_enabled: true
```

Blank issues stay enabled: quick notes remain possible, and `/t-open` can reshape them
later. No `contact_links`. Template ordering in the chooser is alphabetical by
filename, which already puts `initiative` before `task` — acceptable; no ordering
mechanism is added.

## Task form — `task.yml`

| Key | Value |
|---|---|
| `name` | `Task` |
| `description` | `One deliverable, one PR. The shape /t-open produces.` |
| `title` | (none — author writes a short imperative title) |
| `labels` | (none) |

Fields, in order:

1. **Goal** — `textarea`, id `goal`, **required**.
   Description: `One paragraph. Self-sufficient: a fresh session must be able to work the task from this issue alone.`
2. **Done when** — `textarea`, id `done-when`, **required**.
   Description: `Observable, checkable criteria.`
3. **Scope** — `input`, id `scope`, **required**.
   Description: `One line: the paths or area this may touch.`
4. **Non-goals** — `textarea`, id `non-goals`, optional.
   Description: `Explicit exclusions. Each deferred item gets its own issue — open it now.`

No Part-of or Blocked-by field — set both afterward as native relations in the
tracker's sidebar (see above).

The form applies no label. The workflow has no lanes (ADR-001), so there is no
per-issue routing choice for the form to carry; `initiative` and `cancelled` are
applied by the skills that own them. Blocked state is derived from the native
`blockedBy` field, not from a label.

## Initiative form — `initiative.yml`

| Key | Value |
|---|---|
| `name` | `Initiative` |
| `description` | `Tracking issue: overall intent plus ordered child tasks. Several PRs.` |
| `title` | (none) |
| `labels` | `["initiative"]` — GitHub silently drops a label that does not exist, so an initiative opened before `.t-workflow/scripts/github-bootstrap.sh` has run carries none. `/t-status` says so rather than reporting no initiatives. |

Fields, in order:

1. **Goal** — `textarea`, id `goal`, **required**.
   Description: `The overall intent and constraints settled so far. Self-sufficient.`
2. **Tasks** — `textarea`, id `tasks`, **required**. Free-text planning notes only —
   children are linked as native sub-issues (set in the tracker's sidebar for a
   hand-opened initiative), not tracked in this field; `subIssuesSummary` is the
   progress source, matching t-open (ADR-003).
   Description: `Notes only — children are linked as native sub-issues, not tracked here. One per line, e.g. "#151 ..." — list only children that are already clear. If the decomposition is unknown, the only child is a design task whose merged output determines the rest.`

No Scope or Blocked-by: tracking issues have neither, matching t-open. An
initiative opened by hand with unclear decomposition still needs its design child
opened as a separate (task-form) issue, linked to it as a sub-issue.

## Revisit triggers

- t-open's body template changes shape.
- A third recurring issue shape emerges (e.g. workflow friction reports).
- A non-GitHub tracker becomes the active backend — implement its equivalent templates
  and note them in `docs/adapters/TRACKER.md`.
