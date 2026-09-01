# 497 — Restore console tab close button, always-show kebab, local-only Folder
Issue: #497

## Asked
Restore the close button on the console tab strip so both the project console page and
the issue page show a direct × to close a tab, without needing to open the overflow menu
(#480 had folded it, along with Shell and Folder, into a hover-revealed kebab menu).
Alongside that, the kebab (⋮) overflow trigger on each live console tab should be
visible at all times rather than only appearing on hover. Finally, the "Folder" item in
that overflow menu should only be offered when locklane is being accessed locally (host
= localhost), since revealing a worktree in the OS file manager only makes sense for a
local install.

## Done when
- Each live console tab on the project console page and on the issue page shows a close
  (×) button that triggers the existing close-with-confirmation flow.
- The ⋮ kebab trigger on each live console tab is always visible (not gated on
  hover/focus) on both pages.
- The "Folder" menu item in the tab overflow menu only renders when
  `window.location.hostname === 'localhost'`; the "Shell" and "Close" items remain in
  all cases.
- Client test suite passes (`client/` unit specs, including the console-tabs component).

## Explicitly not
- No change to the confirm-on-close dialog or the close endpoint itself.
- The Folder/"reveal" feature's engine endpoint is unchanged; this only gates its menu
  entry.

## Decisions made along the way
- The restored quick-close button is a new always-visible `.tab-close-quick` button
  sitting next to the overflow trigger, rather than reusing the `.tab-close` class —
  that class already names the overflow menu's own "Close" menu item (#480), which the
  issue keeps in place alongside the new button; giving them different classes avoids
  two elements answering to the same selector (agent, 2026-08-31).
- The quick-close button is always visible (no hover/focus gating), matching how the
  close button behaved before #480 collapsed it into the menu, and matching the same
  "always visible" treatment the issue asks for the kebab trigger — neither needs a
  hover to appear now (agent, 2026-08-31).
- `isLocalHost` reads through a small `protected currentHostname()` seam instead of
  calling `window.location.hostname` directly in the getter, so a unit test can spy on
  it — Chrome (via Jasmine's `spyOnProperty`) refuses to override
  `window.location.hostname` itself, since it is not a configurable property
  (agent, 2026-08-31).

## Deviations / notes
- none
