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

**The two machine-read fields are the exception.** `Part of` and `Blocked by` are not
prose: `/t-work`'s blocker gate, `/t-status`'s blocked state, and `/t-cancel`'s dependent
sweep all read the literal tokens `Part of: #n` and `Blocked-by: #n`. A form cannot put a
token in its heading, so it is handled from both ends: the fields' descriptions and
placeholders instruct the person to type the whole token as the value, **and** the three
consuming skills also accept a bare `#n` sitting under the `### Blocked by` / `### Part
of` heading. Either shape counts as blocked. Neither end may be removed without the
other: a bare number that no consumer recognises silently disarms the blocker gate, which
is the one failure ADR-001 §D3 exists to prevent.

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
5. **Part of** — `input`, id `part-of`, optional. **Machine-read.**
   Description: "Its tracking issue. Write the whole token, `Part of: #150`. Leave empty for standalone tasks."
   Placeholder: `Part of: #150`
6. **Blocked by** — `textarea`, id `blocked-by`, optional. **Machine-read.**
   Description: "One per line. Write the whole token, `Blocked-by: #151`, so the reference stays readable wherever it is quoted."
   Placeholder: `Blocked-by: #151`

The form applies no label. The workflow has no lanes (ADR-001), so there is no
per-issue routing choice for the form to carry; `initiative` and `cancelled` are
applied by the skills that own them. Blocked state is derived from `Blocked-by:` lines
in the body, not from a label.

## Initiative form — `initiative.yml`

| Key | Value |
|---|---|
| `name` | `Initiative` |
| `description` | `Tracking issue: overall intent plus ordered child tasks. Several PRs.` |
| `title` | (none) |
| `labels` | `["initiative"]` — GitHub silently drops a label that does not exist, so an initiative opened before `scripts/github-bootstrap.sh` has run carries none. `/t-status` says so rather than reporting no initiatives. |

Fields, in order:

1. **Goal** — `textarea`, id `goal`, **required**.
   Description: `The overall intent and constraints settled so far. Self-sufficient.`
2. **Tasks** — `textarea`, id `tasks`, **required**.
   Description: `Task list of children, one per line: "- [ ] #151 ..." — list only children that are already clear. If the decomposition is unknown, the only child is a design task whose merged output determines the rest.`

No Scope or Blocked-by: tracking issues have none, matching t-open. An
initiative opened by hand with unclear decomposition still needs its design child
opened as a separate (task-form) issue.

## Revisit triggers

- t-open's body template changes shape.
- A third recurring issue shape emerges (e.g. workflow friction reports).
- A non-GitHub tracker becomes the active backend — implement its equivalent templates
  and note them in `docs/adapters/TRACKER.md`.
