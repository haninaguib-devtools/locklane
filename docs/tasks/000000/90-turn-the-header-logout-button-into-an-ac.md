# 90 — Turn the header logout button into an account popup menu
Issue: #90 · Part of: #87

## Asked
Replace the current flat "logout" button in the topbar with a popup menu opened from the
user's avatar: a header showing the user's name and email, a separator, then "Settings"
and "Sign out" items — matching portstow's menu pattern, styled with locklane's own
colors. "Settings" opens an empty settings dialog shell (no content wired yet); "Sign
out" keeps today's logout behavior.

## Done when
- The avatar button in the topbar toggles the popup menu, and it dismisses on an outside
  click.
- The menu shows the user's name and email, a separator, then Settings and Sign out.
- Settings opens a dialog (backdrop + popup, matching `add-project-popup`'s visual
  pattern) with just a title bar and an empty body for now.
- Sign out calls the existing `AuthService.logout()`, unchanged from today.

## Explicitly not
- 2FA content inside the dialog — separate task, #91.

## Decisions made along the way
- The menu header shows the username only, with no email line (hani, 2026-08-26). The
  account has no email to show: `users` in `engine/src/main/resources/schema.sql` stores
  `username`, `password_hash` and `created_at` only, and `GET /api/auth/me` returns
  `{"username": …}`. Adding one means a schema column, a config default, an engine change
  and its tests — well outside this task's scope. Recommended as a follow-up issue rather
  than done here.

## Deviations / notes
- Scope stretched to `client/src/app/services/auth.service.ts` and its spec, beyond the
  issue's stated `client/src/app/app.component.*` plus the new dialog component (hani,
  2026-08-26, in the same exchange as the decision above). `AuthService` already fetches
  `GET /api/auth/me` but discarded its body, so no component could learn who is signed
  in. The change is additive: a `username` signal filled on login and on session restore,
  cleared on logout. Without it the menu header has nothing to display at all.
