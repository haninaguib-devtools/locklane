# 366 — Sidenav status label hidden unconditionally under Open filter
Issue: #366

## Asked
In the sidenav's per-row issue view, the status label (open/closed) is currently hidden
whenever the "Open" filter checkbox (`hideShipped`) is checked, regardless of the row's
actual status. This is wrong: an issue can stay visible while the Open filter is on even
though it is actually CLOSED, because `tree-filter.ts` always keeps a row visible if it
has an open console, independent of shipped/closed state. When that happens (e.g. the
console's issue just closed via `/t-ship`), hiding the status label removes the only UI
signal telling the user the issue is now CLOSED. The label should only be hidden when it
would be redundant with the filter itself — i.e. when the row's own status is OPEN and
the Open filter is active — not unconditionally.

## Done when
- In `client/src/app/components/sidenav/sidenav.component.html`, the `.issue-state` span's
  visibility condition changes from `@if (!hideShipped)` to something equivalent to
  "show unless (hideShipped && node.state === 'OPEN')" — i.e. hide only when the row is
  OPEN and the Open filter is checked.
- The existing `sidenav.component.spec.ts` specs for this label (added under #351,
  "hides the per-row status label while hideShipped is checked" / "shows the per-row
  status label once hideShipped is unchecked") are updated to also cover: with
  `hideShipped` true, an OPEN row's label stays hidden, but a CLOSED row's label (kept
  visible only because it has an open console, per the existing #263 test fixture) is
  shown.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
none

## Decisions made along the way
- none

## Deviations / notes
- Kept both existing #351 specs unchanged (still valid under the new condition, since
  their default tree has no OPEN row visible alongside a CLOSED one) and added one new
  spec, rather than rewriting the #351 specs in place, since the new
  OPEN-vs-CLOSED-with-open-console distinction needed the console-mock fixture from the
  #263 test that the #351 specs don't set up.
