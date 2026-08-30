# 396 — Overview tab: link the title to GitHub, show tags as pills, reorder the details
Issue: #396

## Asked
The issue page's Overview tab makes its header and its details list easier to act on. The
issue title becomes a link to the issue on GitHub, so a person reading the overview can
get to the real issue in one click. The details list is reordered to read the way someone
works: where the code lives, what the task wrote down, then whether CI is happy. The
tags, which today sit as a comma-joined line in the middle of that list, move up under
the one-line description in the header and are shown as small pills — so the top of the
page carries the issue's identity (title, gist, labels) and the list below is purely
"where the work lives".

## Done when
- In `issue-header.component.html`, the title text is an anchor to
  `<repoWebUrl>/issues/<number>` with `target="_blank" rel="noopener"`; the `#<number>`
  span stays outside the anchor. When no repo web URL is known, the title renders as
  plain text exactly as it does today.
- The header renders the issue's labels below the description as individual pills, and
  renders nothing (no empty row, no stray gap) when `issue.labels` is empty.
- `overview-tab.component.html` no longer contains a `tags` `<dt>`/`<dd>` pair, and its
  remaining `<dt>`s appear in this order: `branch & PR`, `record`, `checks`.
- Component specs cover: title anchor href and its absence without a repo URL; pills
  rendered per label and absent when there are none; the details `<dt>` order.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Changing what the checks row says — that is #397.
- Any engine or API change; the header needs `repoWebUrl`, which the page already has.
- Restyling the rest of the Overview tab (body card, sessions rail).

## Decisions made along the way
- The header's specs go through Angular's `TestBed` rather than the plain
  `new IssueHeaderComponent()` style the existing specs use, because the new criteria are
  about rendered DOM (an anchor's `href`, the pill elements) rather than a getter's
  return value. The existing `shortDescription` unit tests are left as they are.

## Deviations / notes
- One line outside the issue's stated Scope: `main-content.component.html` now passes
  `[repoWebUrl]="repoWebUrl"` to `<app-issue-header>`. The header cannot build the issue
  link without it, and the issue's own Non-goals note that the page already has the
  value; nothing else in that file changed.
