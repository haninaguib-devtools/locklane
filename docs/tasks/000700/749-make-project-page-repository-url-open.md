# 749 — Make project page repository URL open in a new tab
Issue: #749

## Asked
On the project summary page, the repository line shows the git URL as plain text. Make
it a clickable link that opens the repository in a new browser tab, so a person can jump
to the repo's page on its forge without leaving the project summary.

## Done when
- The repository value renders as an `<a>` link pointing at `project.gitUrl`.
- Clicking it opens a new tab/window (`target="_blank"`), with `rel="noopener noreferrer"`
  so the new tab cannot reach back into the opener.
- Existing visual styling (the `mono` class) is preserved.
- A case where `project.gitUrl` is empty/undefined is handled the same way the other
  facts in that list handle a missing value (falls back to `—`, no broken/empty link).

## Explicitly not
- No transformation of the URL's shape (e.g. rewriting an SSH-alias remote to an https
  one) — the engine already always stores `gitUrl` as an `https://github.com/...` URL
  (`GitRemoteUrl.normalize`), so the value reaching this component is already
  link-openable.

## Decisions made along the way
- none

## Deviations / notes
- none
