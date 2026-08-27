# 175 — Move past-sessions list into a right-hand column on the Overview tab
Issue: #175

## Asked
On the Overview tab, the "past sessions" list currently sits as a stacked card above
the issue body, pushed further down the page as the body grows. Move it into its own
column on the right, running the full height of the content area, so it stays visible
alongside the body instead of being pushed down by it. Confirmed mockup:
https://claude.ai/code/artifact/0271c288-93ce-44c8-b3d3-a63394f02019

## Done when
- The Overview tab lays out as two columns: the existing details/body content on the
  left, and the past-sessions list in a fixed-width column on the right, matching the
  mockup's proportions.
- The right column scrolls independently when the session list is longer than the
  visible area; the left column keeps its own independent scroll too.
- Behavior is unchanged: reopening a session, the busy/disabled state, and the
  no-sessions case (column simply doesn't render) all still work as they do today.
- `client/src/app` build and existing tests pass.

## Explicitly not
- No change to what's in the session list, how reopening works, or the Overview tab's
  other content (details, body).

## Decisions made along the way
- Rail width set to 220px (agent, 2026-08-27): the mockup's 190px rail sits in a
  miniature shell (~950px content, 11px fonts); 220px keeps the same proportion at the
  app's real font sizes and comfortably fits tool + reopen + wrapped timestamp.
- Session-row reordering (tool, reopen, timestamp-below) done purely in
  `session-list.component.css` via flex `order`/`wrap` (agent, 2026-08-27): the issue's
  scope names only the CSS file, so the template's DOM order is unchanged.

## Deviations / notes
- none
