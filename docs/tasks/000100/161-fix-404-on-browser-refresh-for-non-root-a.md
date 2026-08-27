# 161 — Fix 404 on browser refresh for non-root Angular routes
Issue: #161

## Asked
When a user refreshes the browser (or opens a direct link) on any Angular client-side
route other than the root path (e.g. `/projects/42/issues/7` or `/projects/42/issues`),
the Spring Boot engine returns a 404 instead of serving the Angular app, so the app never
loads. `SpaFallbackController` only forwarded the single old path `/issues/{id}` to
`index.html`; the client's routes were since renamed to `projects/:projectId/issues/:id`
and `projects/:projectId/issues`, so those routes — and any other non-root client-side
route — fell through to Spring's default 404 instead of the SPA shell.

## Done when
- A browser refresh (or a fresh GET request) on `/projects/{id}/issues/{issueId}` and on
  `/projects/{id}/issues` returns the Angular `index.html` (200), not a 404.
- The fix generalizes to Angular client-side routes rather than enumerating each one, so
  adding a future top-level route doesn't require another server-side edit.
- `/` (root) and existing API routes continue to behave exactly as before.
- A test (or tests) exercises reload-on-deep-link behavior for a non-root route.

## Explicitly not
No changes to the Angular routing table (`client/src/app/app.routes.ts`) itself.

## Decisions made along the way
- Replaced the per-route `@GetMapping` enumeration with a Boot-standard custom
  `ErrorController`: every unmapped GET already lands at `/error` via Boot's own
  error-page mechanism, so it's the one place that sees any current or future
  client-side route with zero per-route wiring. Requests under `/api` or `/ws` keep
  the ordinary JSON error response (reproduced from `ErrorAttributes` directly, since
  registering our own `ErrorController` bean makes Boot's autoconfiguration back off
  its default `BasicErrorController`); everything else forwards to `index.html`.
  (hani, 2026-08-27)
- A regex catch-all `@GetMapping` (the other common fix for this class of bug) was
  considered and rejected: `RequestMappingHandlerMapping` is checked before the
  low-priority static-resource handler mapping, so a broad pattern risks shadowing the
  Angular build's own hashed static assets (`main-*.js`, `styles-*.css`, etc.) unless
  the pattern is exactly right. The error-controller path only activates once nothing
  else — including static resource resolution — has already matched, so there's no such
  risk. (hani, 2026-08-27)

## Deviations / notes
- The old `SpaFallbackControllerTest` was a `@WebMvcTest` unit test hitting the
  controller's own explicit mapping directly. The new mechanism only manifests through
  the servlet container's real error-page forwarding, which `@WebMvcTest`/`MockMvc`
  (even under `@SpringBootTest`'s default `MOCK` environment) does not exercise — so the
  replacement test uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` with
  `TestRestTemplate` against the real embedded server, matching the pattern already used
  by this module's other `*IntegrationTest`s (e.g. `WebSocketOriginRestrictionIntegrationTest`).
- `./mvnw -B test` from the repo root also runs the `client` module's Angular suite
  (`npm run test:ci`, headless Chrome). This sandbox has no Chrome binary
  (`CHROME_BIN` unset, no `chromium`/`google-chrome` on `PATH`), so that execution fails
  for an environment reason unrelated to this task's diff, which touches only
  `engine/**`. Reported here rather than worked around.
