# 433 — Extend per-project background tint to the issue pages, and tint the selected-tab underline
Issue: #433

## Asked
A project's own accent color (#427/#428) already tints the background of its summary
page, so a person can tell at a glance which project they're in. Extend that same tint
to the issue page (header, flow strip, tab strip, and the overview/terminal tab bodies),
so the whole project — not just its summary screen — carries the accent. The project's
own console page is explicitly excluded: it stays on the plain, untinted background.

Beyond the page background, the selected-tab underline in the issue page's console tab
strip (`app-console-tabs`, currently `border-bottom-color: var(--accent)` — the global
accent) should use the project's own accent color instead, so the tab indicator matches
the rest of the tinted page around it.

## Done when
- Opening an issue inside a project that has an accent color set shows the same
  background tint (`deriveProjectBackgroundTint`) behind the issue header, flow strip,
  tab strip, and tab bodies (overview tab and terminal) that the project summary page
  already shows.
- A project's own console page (`app-project-console`, `/projects/:id/console`) keeps
  its current plain, untinted background — unaffected by this change.
- The active tab's underline in the console tab strip (`console-tabs.component.css`,
  `.tab-wrap.active`) renders in the current project's accent color when one is set,
  falling back to the existing global `--accent` for a project with none set.
- A project with no accent color set looks exactly as it does today on every affected
  page — no visual regression.
- `./mvnw -B test` and the existing Angular unit tests for the touched components pass.

## Explicitly not
- Changing the tint formula itself (`TINT_RATIO`, the blend-with-white math).
- Dark-mode-specific tuning, a per-user accent, or any change to what the global accent
  setting controls.

## Decisions made along the way
- Investigated the current implementation before changing anything: `.project-pages`'
  inline `[style.background]="projectBackgroundTint()"` (app.component.html) already
  applies uniformly to all three of its children — `app-project-console`,
  `app-main-content`, and `app-project-summary` — with no exclusion for the console
  route. `ProjectConsoleComponent`'s own template has no full-bleed opaque wrapper that
  would incidentally mask this, so today the console page silently already gets tinted
  too, which #433's Done-when says it must not. Fixed by making the wrapper's tint
  conditional on `!onProjectConsole()`, rather than leaving it as a pure additive
  change (haninaguib, 2026-08-30).
- The issue page's header/flow-strip/tab-strip (`issue-header`, `flow-strip`,
  `console-tabs`) are full-bleed bars with their own opaque `background: var(--panel)`,
  unlike the project summary page's cards, which float over an otherwise-transparent
  backdrop and so already reveal `.project-pages`' tint in the gaps around them. Full
  bleed means there is no gap for a bar's background to show through — reproducing "the
  same tint behind it" here means the bar's own background becomes that tint, not
  `var(--panel)`, when one is set. Implemented via two CSS custom properties set as
  inline styles on `.project-pages` — `--project-tint` (the same value bound to its own
  `background`, `null`/absent on the console route or with no accent set) and
  `--project-accent` (the project's raw accent hex, same conditions) — consumed by
  descendant stylesheets as `var(--project-tint, var(--panel))` /
  `var(--project-accent, var(--accent))`. Custom properties inherit through Angular's
  emulated view encapsulation like any other CSS property, so this needed no
  `@Input()` plumbing through `MainContentComponent` (haninaguib, 2026-08-30).
- `overview-tab.component.css`'s `.overview` and `.sessions-rail` use `var(--window)`
  rather than `var(--panel)` — kept `var(--window)` as the fallback there
  (`var(--project-tint, var(--window))`) to preserve today's exact look when no accent
  is set (haninaguib, 2026-08-30).
- `terminal.component.css`'s `.terminal` is a fixed dark background
  (`#1c1a17`) that fills its box edge-to-edge — there is no visible gap for a page tint
  to ever show through a terminal, tinted project or not, so it needed no change; the
  issue's mention of "terminal tab bodies" is satisfied vacuously (haninaguib,
  2026-08-30).

## Deviations / notes
- none
