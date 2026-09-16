# 930 — Filter the sidenav issue tree by GitHub author
Issue: #930

## Asked
Let a person narrow the sidenav's issue tree to the issues one GitHub login opened. Today the engine fetches issues with `gh issue list --json number,title,state,labels,body,createdAt,updatedAt,parent` (`CliGhClient`), so neither `GhIssue` nor `TreeNode` carries the author and the client cannot filter on it. Add `author` (the GitHub login of the issue's creator) end to end: to the `gh` field list and `GhIssue`, to the server `TreeNode` and its client mirror, and to the sidenav as an author picker that sits beside the existing text filter and tag filter and composes with them and with `hideShipped`. The picker's choices are the distinct logins present in the loaded tree; an empty choice means no author filtering. "Author" is GitHub's notion of who opened the issue, so an issue an agent opened under a person's account is attributed to that person.

## Done when
- `grep -n '"number,title,state,labels,body,createdAt,updatedAt,parent,author"' engine/src/main/java/dev/locklane/engine/github/CliGhClient.java` matches, and `GhIssue` has a `String author` component populated from `author.login` (empty string when absent), keeping the existing pre-#325 constructor working.
- `TreeNode` (server) and `TreeNode` in `client/src/app/models/issue.model.ts` both carry `author: string`; `IssueTreeService` copies it verbatim for initiatives, tasks and children.
- The sidenav shows an author picker next to the text and tag filters, listing the distinct authors of the loaded tree; choosing one hides every node whose author differs, and the filter composes with the text filter and `hideShipped` the same way the tag filter does (a pinned issue follows the same rule the tag filter applies to pins).
- Unit tests: `CliGhClient` / `IssueTreeService` tests cover the author mapping; `tree-filter.spec.ts` or `sidenav.component.spec.ts` cover author filtering alone and combined with text.
- `mvn -q -f engine/pom.xml test` and `npm --prefix client test -- --watch=false` pass.

## Explicitly not
- A "requested by" label or body-line convention for issues an agent opened on someone's behalf; GitHub's author is the only source.
- Persisting the chosen author differently from how the existing text and tag filters are persisted.
- Author on `IssueDetail` or the issue header; the sidenav tree is the only consumer.

## Decisions made along the way
- `GhIssue` keeps both older constructors (7-arg pre-#325 and 8-arg with `parent`) delegating to the new 9-arg one with `author = ""`, so no existing test call site changes.
- The author filter is a sixth optional parameter on `filterNode`/`filterTree`/`filterPinnedNode`/`filterPinnedTree`, after `hasOpenAgentSession`, so every existing caller keeps working. It follows the tag filter's rules exactly: ANDed with the others, not exempted by an open agent session, and never removes a pinned entry itself (only its children).
- The sidenav has no visible tag picker today (it passes `[]` for tags), so the author picker sits beside the text filter as a native `<select>` with an "anyone" empty option. Its choices come from the raw loaded trees of every project, sorted; a chosen login that has since left the tree stays listed until cleared so the selection is never silently invisible.
- `filterAuthor` is not persisted across reloads, matching `filterText` and `hideShipped`.

## Deviations / notes
- `author: string` is required on the client `TreeNode` (as the issue asks), which broke type-checking of `TreeNode` literals in three spec files outside the declared scope: `client/src/app/components/project-summary/project-summary.component.spec.ts`, `client/src/app/components/overview/overview.component.spec.ts`, `client/src/app/services/issues.service.spec.ts`. Each got only `author: ''` added to existing literals — the client analogue of the scope's "constructor call sites only" allowance for the engine persistence tests. Nothing else in those files changed.
- Local `scripts/check.sh` (full `./mvnw -B test`): the new/changed GitHub tests pass (`CliGhClientTest` 9/9, `IssueTreeServiceTest` 15/15), but the run reports 16 failures in this machine's known environment-only set (`setsid` and `/bin/true` absent on this macOS, `osxkeychain` credential helper, a `GH_TOKEN` present in the environment, PTY/worktree timing tests). None are in `dev.locklane.engine.github`. CI is the authoritative run.

## Agents
- work: claude-code / claude-fable-5-1
