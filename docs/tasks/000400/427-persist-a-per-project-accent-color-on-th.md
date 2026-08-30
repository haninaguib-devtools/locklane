# 427 — Persist a per-project accent color on the backend
Issue: #427 · Part of: #426

## Asked
Add a per-project accent color that a project's owner can set and that the API returns,
so the client (a separate task) can render each project's pages with a background
tinted from it. Today `ProjectRecord` carries no color/appearance field at all — accent
color is currently only a global, client-side `localStorage` preference
(`AccentThemeStore`), which this task does not touch.

## Done when
- The `projects` table has a new nullable column for the project's accent color (e.g.
  `accent_color`), added via a new Flyway migration following the existing `ADD COLUMN`
  pattern (`V11__AddDisplayNameToWorktreeSessions.java` is the template; this becomes
  `V12__...`), guarded with `SqliteColumns.exists(...)` so it is safe on both fresh and
  existing databases.
- `ProjectRecord` carries the new field, and `ProjectRepository` reads/writes it.
- `ProjectController` exposes the field on project read responses and accepts it on
  whatever endpoint already lets an owner edit project settings (or a new one, if none
  exists yet) — a request setting an unset or invalid value is rejected with a clear
  4xx, not silently ignored.
- Only a project's owner can set its accent color — the existing `owner_user_id`
  authorization check that already gates project mutations applies here too (no new
  authorization surface).
- `./mvnw -B test` passes with tests covering: the migration is idempotent, the field
  round-trips through the repository, and the controller enforces ownership and
  validates the value.

## Explicitly not
- Any client-side change (picker UI, applying the color as a background) — that is the
  paired frontend task, #428.
- Choosing the exact color representation/validation rule (hex string vs. enum of
  presets) beyond "store and return it safely" — left to implementation.

## Decisions made along the way
- none

## Deviations / notes
- none
