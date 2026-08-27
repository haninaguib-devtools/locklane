# 142 — Render issue body as markdown in the Overview tab
Issue: #142

## Asked
The Overview tab of an issue page currently dumps the issue body as raw markdown text (a
`<pre>` block), so headings, lists, and links show as literal `##` and `- [ ]` noise.
Render the body as formatted markdown instead, and dress the section up: a simple rounded
border and a white background, so it reads like a document card rather than a text dump.

## Done when
- Opening an issue's Overview tab shows the body with markdown formatting (headings,
  lists, code fences, links) — no raw `##` visible for a body that uses them.
  Human-judged visually.
- The body section has a rounded border and white background.
- Markdown is rendered safely (HTML sanitized — no script injection from issue bodies).
  Human-judged via code review; a body containing `<script>` must not execute.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
- No markdown rendering elsewhere (PR descriptions, comments) — this touches only the
  Overview tab's issue body.
- No broader Overview tab redesign beyond the body section's border/background.

## Decisions made along the way
- Render markdown with `marked` (to HTML) and sanitize the result with `DOMPurify`
  before binding via `[innerHTML]`; the sanitized HTML is passed through
  `DomSanitizer.bypassSecurityTrustHtml` since DOMPurify has already stripped anything
  dangerous — no unsanitized HTML reaches the DOM.
- "White background" taken literally (`#ffffff`) rather than the app's off-white
  `--window` token: the app has no dark theme, and the issue explicitly asked for a
  document-card look distinct from the surrounding panel.

## Deviations / notes
- none
