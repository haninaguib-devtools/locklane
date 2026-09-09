---
name: t-update
description: Move this repository's t-workflow to a newer release as an ordinary task — the installer opens the issue, branch, record, and PR. Use to update or sync t-workflow.
---

# Update t-workflow

`/t-update [<tag>]`; with no tag, the newest release. Current version: `cat .t-workflow/VERSION`.

1. Run the installer from `main`; it resolves the tag, opens the issue, creates the
   branch, replaces every t-workflow-owned file, appends any config key this repository
   lacks, writes the record, and opens a draft PR:

```bash
curl -fsSL https://raw.githubusercontent.com/t-workflow/t-workflow/main/install.sh | bash -s -- [<tag>]
```

2. Report, from the installer's output: the version moved from and to, the commits
   between them (their subjects carry issue numbers for the why), any config key it
   added, and the PR URL. The diff is the changelog.
3. The update touches protected paths, so a cold review comes before shipping: name
   `/t-drive <id>`, which runs `/t-review` and `/t-ship` and stops at the merge
   question, or the two commands separately.
