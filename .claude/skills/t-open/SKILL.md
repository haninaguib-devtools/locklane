---
name: t-open
description: Turn the conversation into tracker issue(s) — one task, or a parent with children. The only way work starts. Use when goal and done-when are statable.
---

# Open work

Read `.t-workflow/AGENTS.md`. Issues only: no branch, record, or file change here.

**When.** Goal and done-when must be statable; if not, say what is missing and stop.
Do not wait until everything is settled — decisions left in chat are lost.

**Shape.**
- Fits one PR → one task issue.
- Several PRs → a parent issue labelled `initiative` (`issue.sh ensure-label initiative`)
  plus every child that is already clear. Children land on `wip/<parent>-integration`
  one PR each, merged mechanically; the human confirms once, on the parent's PR to
  the trunk (`/t-ship <parent>`). Never guess a decomposition: when it is
  unknown, the parent gets exactly one child, a design task whose merged document
  decides the rest.
- Two levels only. A child that needs children means the parent should be split.

**Body** (omit empty sections; a parent carries Goal and Non-goals only):

```markdown
## Goal
<one paragraph a cold session can work from alone — no references to this chat>

## Done when
<observable criteria; a command, grep, or exit code where possible>

## Scope
<the paths or area this may touch, each in backticks>

## Non-goals
<explicit exclusions>
```

**Commands.** Title short and imperative.

```bash
gh issue create --title "<title>" --body-file <file> [--label initiative]   # prints the URL; the number is its last segment
gh issue edit <child> --parent <parent>            # right after creating each child
gh issue edit <id> --add-blocked-by <blocker>      # each dependency the conversation named
```

Deferred work named in Non-goals that the project intends to do later gets its own
issue now, with `Split from: #<id>` as the body's last line. A non-goal that is only a
boundary ("does not touch billing") stays prose. Ask when unsure which it is.

**Report** in plain language: each issue and what it means, dependencies, assumptions.
Name the next command: `/t-plan <id>` when the Scope touches a protected path
(`.t-workflow/scripts/protected.sh <paths>`) or needs pinning down; `/t-work <id>` otherwise.
