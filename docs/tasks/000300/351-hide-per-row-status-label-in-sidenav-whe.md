# 351 — Hide per-row status label in sidenav when Open filter is active
Issue: #351

## Asked
In the sidenav, each issue/task row shows its status ("open" or "closed") as small
muted text next to the row. The sidenav's "Open" checkbox filter (`hideShipped`, on by
default) already hides every closed row when checked, so with that filter on, every
visible row's status label reads "open" — repeating the same word down the whole list
with no information value. When the "Open" filter is checked, the per-row status label
should not be rendered at all. When it's unchecked (showing both open and closed
issues), the status label should keep rendering as it does today.

## Done when
- With the "Open" filter checked (`hideShipped: true`, the default), no row in the
  sidenav renders visible status text — verified by a component test asserting the
  `.issue-state` element is absent (or empty) for a row while `hideShipped` is true.
- With the "Open" filter unchecked (`hideShipped: false`), rows continue to show their
  status text (`open`/`closed`) exactly as today — covered by a component test.
- The change is purely presentational: `tree-filter.ts`'s filtering logic (which rows
  appear) is untouched; only whether the status text is drawn on the rows that do
  appear changes.
- `./mvnw -B test` passes, including the client test suite with the new/updated
  coverage above.

## Explicitly not
No change to the status label's styling/coloring, to the open-console exemption
behavior (#263), or to `tree-filter.ts`'s filtering logic itself.

## Decisions made along the way
- none

## Deviations / notes
- none
