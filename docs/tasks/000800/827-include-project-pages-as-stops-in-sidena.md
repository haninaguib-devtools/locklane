# 827 — Include project pages as stops in sidenav arrow-key navigation
Issue: #827

## Asked
When a user presses the up/down arrow keys while focused on a row in the sidenav, focus
should be able to land on a project's own page, not only on issue rows. Today,
arrow-key navigation silently skips over project pages and moves only between issues,
so a user arrowing down through a project's issue list jumps straight into the next
project's issues without ever stopping on that project's own page.

## Done when
- Pressing ArrowDown from the last issue row before a project boundary (or ArrowUp
  from the first issue row after one) moves focus and selection onto that project's
  own row/page, instead of skipping straight to the neighboring issue.
- Arriving at a project via arrow keys puts the app in the same state as clicking that
  project's row directly (matching how landing on an issue row via arrow keys today
  mirrors clicking it).
- Existing issue-to-issue arrow navigation, and the click-based project/issue selection
  paths, are unchanged.
- A human confirms the keyboard-only traversal feels correct in the running app (this
  is a UI/interaction-feel judgment a human makes, not something a script can assert).

## Explicitly not
- Full keyboard accessibility for the project header (Enter/Space activation, ARIA
  `role="button"`) — not asked for; only arrow-key traversal is in scope. Flagged in
  Deviations/notes as a possible follow-on.
- A visible `:focus-visible` outline on the landed-on project header —
  `sidenav.component.css`'s `:is(button, input, a):focus-visible` rule does not cover
  a `div`, and the issue's Scope names only the `.ts`/`.html` files. Flagged below,
  proposed as a follow-up issue rather than an in-task drive-by (`AGENTS.md`
  §Conventions).

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Reused the existing generic `onRowKeydown` handler for the project header row
  rather than writing a second handler, and widened its `querySelectorAll` selector
  from `a.row` to `a.row, .section-header` — DOM order already places a project's
  header immediately before its own issue rows, so no other ordering logic was needed
  to make the header a stop between the previous project's last issue and this
  project's own issues (haninaguib, 2026-09-08).

## Deviations / notes
- Out-of-scope discovery: `.section-header` gets no `:focus-visible` outline —
  `sidenav.component.css` line 576 scopes that rule to `:is(button, input, a)`, and a
  `div` matches none of those. The issue's declared Scope names only
  `sidenav.component.ts` and `sidenav.component.html`, so this was left alone rather
  than edited as a drive-by. Recommending a follow-up issue: extend that CSS selector
  (or add an equivalent rule) to cover `.section-header:focus-visible` so a keyboard
  user landing there gets the same visible focus ring a row gets.
- Not adding `role="button"`/Enter-Space activation to `.section-header`: the done-when
  criteria ask only for arrow-key traversal to include the project header as a stop,
  not full keyboard operability of that control. Noted as a possible separate
  follow-up if a human wants it.
