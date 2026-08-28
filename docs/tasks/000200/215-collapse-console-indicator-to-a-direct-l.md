# 215 — Collapse console-indicator to a direct link when only one console
Issue: #215

## Asked
In the header's console indicator, when there is exactly one open console, the button
should behave as a direct link to that console instead of a picker trigger: its label
should read `console` (no count), and clicking it should navigate straight to that
console's page instead of opening the dropdown list.

## Done when
- With exactly one entry in `entries()`, the button's visible text is `console` (not
  `consoles (1)`).
- With exactly one entry, clicking the button navigates directly to that console's page
  (issue console route or ad-hoc console route, matching `jumpTo`'s existing branching)
  and does not open the picker dropdown.
- With zero or two-or-more entries, existing behavior (label `consoles (N)`, `toggle()`
  opening the picker) is unchanged.
- Keyboard activation (Enter/Space on the button) produces the same single-console
  direct-navigation behavior as a click.
- Existing tests for `console-indicator.component` still pass; a new test covers the
  single-console direct-navigation case.

## Explicitly not
No changes to the picker's behavior for 0 or 2+ consoles, and no changes to `jumpTo`'s
navigation logic itself.

## Decisions made along the way
- Added a single `onTriggerClick()` method that branches on `entries().length === 1`
  and calls `jumpTo()` directly rather than `toggle()`, instead of branching in the
  template — keeps the logic unit-testable directly on the component instance
  (haninaguib, 2026-08-27).
- Enter/Space activation needed no separate handler: the trigger is a native `<button>`,
  which already dispatches a `click` event for both keys, so the existing `(click)`
  binding covers keyboard activation for free (haninaguib, 2026-08-27).

## Deviations / notes
- none
