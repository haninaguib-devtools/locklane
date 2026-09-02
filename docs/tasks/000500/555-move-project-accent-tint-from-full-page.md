# 555 — Move project accent tint from full-page background to header only
Issue: #555

## Asked
When a project has an accent color set, opening that project currently washes the
entire page background (project summary, issue view, the flow strip, the console tab
strip, and tab bodies) in a lighter tint of that color, and highlights the selected
console tab's underline in the raw accent color. This changes it so a project's accent
color only affects the background of the app's persistent header bar (the bar with the
logo, title, "+ add project" button, and account menu), using the same lighter-tint
calculation used today. Every other surface reverts to its plain, non-tinted background
regardless of the selected project's accent color. The header tint applies whenever a
project is selected, including while viewing that project's own console page (the
console page's carve-out from the old full-page tint goes away, since the effect now
lives on the always-visible header instead). This is a client-side rendering change
only — the accent color pickers and their backend persistence are untouched.

## Done when
- With a project that has an accent color set selected, the topbar background reflects
  that project's lighter tint (the same blend `deriveProjectBackgroundTint` produces
  today), including while viewing that project's own console page.
- With no project selected (the Overview page), or a project with no accent color set,
  the topbar shows its plain default background.
- `.project-pages`, `issue-header`, `flow-strip`, `console-tabs`, and `overview-tab`
  show their plain default backgrounds regardless of the selected project's accent
  color.
- `console-tabs`' selected-tab underline uses the global `--accent` color, not the
  project's raw accent color.
- `./mvnw -B test` passes, with client unit tests updated to match the new behavior.
- `git diff` touches only files under Scope.

## Explicitly not
- The Settings dialog's global "Appearance" accent picker
  (`client/src/app/components/settings-dialog/`,
  `client/src/app/services/accent-theme-store.ts`) — untouched, it already has no
  effect on project backgrounds.
- The per-project accent color picker on the Project Summary page, and its backend
  persistence (`accent_color` column, `ProjectController`/`ProjectRepository`) —
  untouched; this task only changes how the client renders an already-stored value.
- No new accent-related UI.

## Decisions made along the way
- none

## Deviations / notes
- none
