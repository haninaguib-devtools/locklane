# 265 — Redirect to project page when closing the last open console
Issue: #265

## Asked
When a person closes the last console still open on a project's console page, the page
currently starts a brand-new default-agent console automatically instead of letting them
leave the console view empty. Closing the last console should instead navigate back to
the project page, the same place the `+` affordance already lives if they want to start
a new console later.

## Done when
- Closing the only open console on a project's console page navigates to the project
  page, and does not call the default-agent auto-start.
- Closing a console when other consoles remain open still behaves as today (selects
  another open console, no navigation).
- Landing on the console page directly with zero open consoles still auto-starts a
  default-agent console (unchanged) — this task only changes the close-of-last-console
  path, not the on-load path.

## Explicitly not
No change to the on-load auto-start behavior (`load()` calling `startDefault()` when a
project has zero sessions on page landing) — that stays as shipped in #256.

## Decisions made along the way
- none

## Deviations / notes
- none
