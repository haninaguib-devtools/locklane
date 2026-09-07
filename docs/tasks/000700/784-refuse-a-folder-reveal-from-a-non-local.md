# 784 — Refuse a Folder reveal from a non-local browser server-side
Issue: #784

## Asked
"Folder" on an agent tab launches the OS file manager on the engine's host. Today the
only thing stopping a browser on another machine from doing that is the client hiding
the menu item when the page hostname is not `localhost`; the
`POST /api/projects/{projectId}/consoles/{id}/reveal-in-file-manager` endpoint itself
accepts any authenticated owner of the project, from anywhere. Since locklane is
multi-user (ADR-105), a remote user can pop file-manager windows on the host's desktop
with one request. Make the endpoint apply the same server-side loopback check the
desktop-IDE launch introduced in #781: honoured only when the request's peer address is
loopback and it carries no `Forwarded` or `X-Forwarded-For` header, otherwise 403 and
nothing launched. Also list the endpoint in `SecurityConfig`, so an anonymous call is a
401 rather than the 500 on a null principal that #655's record noted and left for its
own issue.

## Done when
- `reveal-in-file-manager` reuses #781's loopback check (`LoopbackRequests`). Engine
  tests cover: loopback peer launches; non-loopback peer is 403 with no launch; loopback
  peer plus `X-Forwarded-For` is 403 with no launch.
- `SecurityConfig` lists `/api/projects/*/consoles/*/reveal-in-file-manager` as
  `authenticated()`; a test covers the anonymous 401.
- Existing behaviour for a `localhost` browser is unchanged: the same owner-only
  visibility rule (404 for a console the caller may not see), 204 on launch.
- `./mvnw -B test` passes.

## Explicitly not
- No client change: the item stays hidden off-`localhost` exactly as now.
- No change to what "Folder" launches or how.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The loopback check runs after the owner-only visibility check, mirroring `open-ide`'s
  order (404 first, then 403): a remote caller probing console ids of another project
  still learns nothing beyond what it could before. (agent, 2026-09-07)
- The anonymous-401 test is a Spring Boot MockMvc route test in the same shape as
  `InstalledIdesRouteIntegrationTest` (#781) and `TemplatesRouteIntegrationTest`, since
  `SecurityConfig` ends in `permitAll` and only a test over the real filter chain proves
  the matcher exists. (agent, 2026-09-07)

## Deviations / notes
- none
