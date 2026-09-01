# 480 — Collapse console tab actions into an overflow menu
Issue: #480

## Asked
Each console tab in `ConsoleTabsComponent` (shared by both project consoles and issue
consoles) currently shows up to three always-visible per-tab icon buttons — open shell,
open folder/reveal, and close — revealed on tab hover. Replace all three with a single
overflow ("kebab") button per tab; clicking it opens a small popup menu listing Shell,
Folder (only when applicable to that tab, same as today), and Close as menu items. This
follows the existing kebab-menu pattern already used in `sidenav.component` (a `⋮`
trigger button toggling a `role="menu"` popup, closed via an outside-click listener),
rather than inventing a new interaction. All three actions move into the menu uniformly
— none stay as a separate always-visible icon.

## Done when
- `ConsoleTabsComponent`'s per-tab markup renders one overflow trigger button instead of
  the three separate `.tab-shell` / `.tab-reveal` / `.tab-close` buttons.
- Clicking the trigger opens a popup menu (`role="menu"`) with menu items
  (`role="menuitem"`) for Shell, Folder (present only when reveal is applicable to that
  tab, matching today's conditionality), and Close, each invoking the same existing
  handler (`openShellAt`, `revealTab`, `closeTab`) it does today.
- The menu closes on an outside click and after an item is chosen, and only one tab's
  menu is open at a time — matching the behavior already established by the sidenav's
  kebab menu.
- Behavior is unchanged in both places `ConsoleTabsComponent` is used: project consoles
  (`project-console.component.html`) and issue consoles (`main-content.component.html`).
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- Changing which actions exist or their underlying behavior (shell/folder/close logic
  itself is untouched).
- Reworking the sidenav's kebab menu — it's a reference pattern only, not a target of
  this change.
- Extracting a shared overflow-menu component/directive for reuse across the sidenav and
  the tab strip — worth considering later if a third consumer shows up, but not required
  to ship this.

## Decisions made along the way
- Menu items render as plain text labels ("Shell", "Folder", "Close"), dropping the
  inline SVG icons the old always-visible buttons used — mirrors the sidenav's own
  kebab menu items (plain text "Pin"/"Unpin"), the reference pattern this issue asks to
  follow (agent, 2026-08-31).
- "Folder (only when applicable to that tab, same as today)": today's only
  conditionality is structural — the reveal button never appears on the pinned Overview
  pseudo-tab because that tab's markup is a separate block outside the `@for` loop over
  real tabs. There is no other per-tab condition on reveal today, so the new menu keeps
  that same structural exclusion and adds no new per-tab condition inside the loop
  (agent, 2026-08-31).
- The overflow trigger fades in on tab hover/focus, like the old `.tab-shell` /
  `.tab-reveal` icons — not permanently visible like the old `.tab-close` was. Since the
  trigger now carries all three actions including close, following the sidenav
  kebab's own hover-reveal treatment (`.row:hover .kebab`) was preferred over always
  showing it, for a quieter tab strip (agent, 2026-08-31).

## Deviations / notes
- `console-tabs.component.spec.ts` (outside the issue's literal Scope line, which names
  only `.html`/`.ts`/`.css`) needed updating alongside the markup: several existing
  specs assert the old always-visible `.tab-shell`/`.tab-reveal` icons are present and
  clickable without opening a menu first, which is exactly the interaction this issue
  replaces. Left unchanged, those specs would fail against the new markup. Treated as
  part of implementing this component's own described behavior change, not drive-by
  work on an unrelated file — updated to open the tab's menu first, then act on the
  item inside it (agent, 2026-08-31).
