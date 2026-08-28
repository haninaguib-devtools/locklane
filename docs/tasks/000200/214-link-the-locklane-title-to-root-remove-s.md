# 214 — Link the LockLane title to root, remove sidenav Overview entry
Issue: #214

## Asked
Replace the sidenav's "Overview" link with the "LockLane" brand title in the topbar
acting as the way back to the root/overview page. The two do the same job — clicking
the logo/title to go home is the more familiar pattern, and it frees up space in the
sidenav.

## Done when
- The "LockLane" brand text in `client/src/app/app.component.html` is a `routerLink="/"`
  anchor, with a hover/pointer affordance so it reads as clickable.
- The `overview-entry` anchor is removed from
  `client/src/app/components/sidenav/sidenav.component.html`.
- The `isOverviewActive()` method and its usage are removed from
  `client/src/app/components/sidenav/sidenav.component.ts`.
- Tests referencing the Overview sidenav entry (`sidenav.component.spec.ts`) are updated
  or removed as needed; the suite passes.
- Clicking the brand title from any page navigates to `/`.

## Explicitly not
No active-state indicator on the title for when the Overview page is showing.
No other sidenav layout or navigation changes beyond removing the Overview row.

## Decisions made along the way
- none

## Deviations / notes
- none
