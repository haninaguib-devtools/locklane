# 61 — Fix console picker popup: mispositioned, no outside-click/ESC dismiss
Issue: #61

## Asked
The "+" button in the console tabs bar opens a picker (where to open the console, which
agent) that is broken in two ways: it renders pushed to the right edge of the page
instead of anchored near the button that opened it, and there is no way to dismiss it
except clicking "open" — clicking outside the picker or pressing ESC currently does
nothing.

## Done when
- Opening the picker positions it against the "+" button (not the right edge of the tab
  bar/page), regardless of how many console tabs are open.
- Clicking anywhere outside the open picker closes it without submitting.
- Pressing ESC while the picker is open closes it without submitting.
- `console-tabs.component.spec.ts` covers the outside-click and ESC dismiss behavior.

## Explicitly not
none

## Decisions made along the way
- none yet

## Deviations / notes
- none
