# 975 — Open the row Labels popup leftward so it is not clipped by the main pane
Issue: #975

## Asked
The per-row "Labels" popup added by #963 (part of #961) opens to the right of the row's kebab (⋮) button. The kebab sits at the sidebar's right edge, so the popup hangs off the sidebar and is hidden behind the main content pane; only a sliver with checkboxes is visible. The popup reuses the `.picker` class, whose generic rule in `client/src/app/components/sidenav/sidenav.component.css` (`.picker { left: 0; right: auto; ... }`, around line 109) is meant for the top filter-bar pickers (authors, labels) that anchor to a left edge. Inside a row's `.menu-wrap` the popup should anchor to the right edge and open leftward, exactly as the row's `.menu` (Pin / Labels) already does. Add a scoped override such as `.menu-wrap .picker { right: 0; left: auto; }` so the row popup opens fully inside the sidebar, leaving the top filter-bar pickers unchanged.

## Done when
- Opening Labels from any issue row's kebab shows the whole label-checkbox popup inside the sidebar, its right edge aligned with the kebab, not clipped by the main content pane.
- The top filter-bar `authors` and `labels` pickers still open leftward-anchored (left edge under their button) as before.
- `grep -n "menu-wrap .picker" client/src/app/components/sidenav/sidenav.component.css` matches.
- The configured `check` command passes; a sidenav spec asserting the row picker is rendered inside `.menu-wrap` is added if practical.

## Explicitly not
Changing the labels feature's behaviour or endpoints; restyling the top filter-bar pickers; any engine change.

## Decisions made along the way
- One scoped CSS rule, `.menu-wrap .picker { right: 0; left: auto; }`, placed right after the generic `.picker` rule so it wins by specificity without touching the filter-bar pickers. No template change was needed.
- The new spec compares the popup's right edge to its `.menu-wrap`'s right edge via `getBoundingClientRect`, rather than reading `left`/`right` from `getComputedStyle`, which resolves to used pixel values for positioned elements.

## Deviations / notes
- The sidenav stylesheet sits at Angular's 8 kB `anyComponentStyle` error budget; the first attempt failed the build by 26 bytes. Kept within budget inside the scoped file only: dropped the dead `right: auto` from the generic `.picker` rule (the later `.menu, .picker { right: 0 }` already overrode it), reduced the new rule to `left: auto`, and merged the two adjacent `.kebab` blocks. Built size is now 8.00 kB, still under the error line. Raising the budget in `angular.json` is out of scope; proposed as a follow-up.
- `scripts/check.sh` (`./mvnw -B test`): client build and all 1060 Karma specs pass; the engine module reports 10 failures in ProjectCheckoutServiceTest, ProjectWorktreesServiceTest, SessionRegistryReattachTest and BellHookCommandDetachedShapeTest, the known environment-only failures on this Mac, unrelated to this client-only diff. CI is the authoritative run.

## Agents
- work: claude-code / claude-fable-5-1
