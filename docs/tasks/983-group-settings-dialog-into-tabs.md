# 983 — Group settings dialog into tabs
Issue: #983

## Asked
The settings dialog (`client/src/app/components/settings-dialog/`) currently renders
seven distinct settings as one long, flat, vertically-scrolling list of `<section>`
blocks (Default agent, IDE, Appearance, Notifications, Remote Control, Password,
Two-factor authentication — the last of these alone a five-stage flow with a nested
"Backup codes" subsection). It has grown too long. Reorganize it into a tabbed
interface that groups related settings, so a user sees one focused group at a time.

Group the existing sections into three tabs:
- **General** — Default agent, IDE, Appearance
- **Notifications** — Notifications, Remote Control
- **Security** — Password, Two-factor authentication

This project has no Angular Material and no `mat-tab-group` anywhere in the codebase.
Follow the existing hand-rolled tab convention already used in
`client/src/app/components/agent-session-tabs/agent-session-tabs.component.html`: a
`role="tablist"` / `role="tab"` ARIA pattern built from plain buttons/divs and
component-scoped CSS, not a UI library widget. Style the new tab strip with this
component's own CSS custom properties (`--panel`, `--border`, `--accent`, `--muted`,
`--text`, `--faint`, `--bg`, `--danger`, `--mono`), matching the dialog's current look.

This is a pure presentational reorganization: every setting's existing behavior,
handlers, and state (including the IDE tab's `@if (availableIdes().length > 1)`
conditional visibility and the two-factor section's stage machine) must keep working
exactly as today — only how the sections are grouped and switched between changes.

## Done when
- The settings dialog renders three tabs (General, Notifications, Security) using a
  hand-rolled `role="tablist"`/`role="tab"` pattern, with only the active tab's panel
  visible at a time.
- Every one of the seven existing sections appears under its assigned tab, unchanged
  in content and behavior (Default agent, IDE, Appearance under General; Notifications,
  Remote Control under Notifications; Password, Two-factor authentication under
  Security).
- The IDE section's existing conditional (`@if (availableIdes().length > 1)`) still
  hides it when there is nothing to choose between.
- No settings logic, service calls, or component TypeScript behavior changes — this is
  template/CSS reorganization plus the minimal TS needed to track the selected tab.
- `client/src/app/components/settings-dialog/settings-dialog.component.spec.ts` is
  updated to match the new structure and passes.
- `ng test` (or this repo's configured client check) passes for the changed component.

## Explicitly not
- No change to any setting's underlying behavior, store, or service call.
- No introduction of Angular Material or any other UI library.
- No change to the dialog's outer chrome (backdrop, header, close button, Escape-to-close).

## Decisions made along the way
- none

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
