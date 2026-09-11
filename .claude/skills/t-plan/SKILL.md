---
name: t-plan
description: Pin a task's allowed paths, risks, and checks onto its issue as a `## Plan` section. Required before implementing a protected diff. Use after t-open, before t-work.
---

# Plan a task

Read `.t-workflow/AGENTS.md` and the issue (`.t-workflow/scripts/issue.sh view <id>`).
Refuse a parent (`initiative`) issue: it has no diff to plan; name a child instead.

1. Decide what the work must touch. Run `.t-workflow/scripts/protected.sh <paths>` on
   those paths: exit 0 lists the protected ones, which is why this plan is required.
2. Read the code and documents the work depends on. Name every risk you can see: an
   existing behaviour the change could break, a check that cannot cover something, a
   judgment only a human can make.
3. Write the section below onto the issue with `gh issue edit <id> --body-file <file>`,
   keeping the rest of the body verbatim. **An issue carries exactly one `## Plan`.**
   Re-planning replaces the whole section; say in the report what changed and why, and
   `/t-work` writes that into the record's Deviations.

```markdown
## Plan
### Allowed paths
- `<path or glob>` — <why>
### Risks
- <risk> — <how the plan handles it>
### Checks
- `<command>` — <what it proves>
### Human checks
- <a judgment no command settles, or "none">
Planned by: <harness> / <model>
```

`Planned by` names the harness you are running under (`claude-code`, `codex`,
`gemini-cli`, ...; with its version when you can tell) and the exact model id you are
actually running as, never a display name — write `unknown` for whichever you cannot
determine, never a guess. `/t-work` reads it to seed the record's `## Agents` section;
this skill never touches the working tree, so it goes on the issue, not the record.

Allowed paths are binding on `/t-work`: work that needs a path outside them stops and
comes back here. The record `docs/tasks/<id>-<slug>.md` is always allowed. Human checks
are what `/t-review` restates and `/t-ship` shows at the merge gate.

**Report**: what the change will touch, what could go wrong, what the human will have
to judge. Then stop and name `/t-work <id>`.
