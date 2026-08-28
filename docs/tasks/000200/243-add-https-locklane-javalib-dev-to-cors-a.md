# 243 — Add https://locklane.javalib.dev to CORS allowed-origins
Issue: #243

## Asked
The engine is now reachable at `https://locklane.javalib.dev` via an nginx proxy
manager instance that terminates TLS in front of it, but `application.yml`'s CORS
`allowed-origins` still only lists `http://localhost:4200` and `http://localhost:8080`.
Add the deployed origin so browser requests from that origin aren't rejected by CORS.
This closes the second of #223's three done-when criteria.

## Done when
- `engine/src/main/resources/application.yml`'s `allowed-origins` list includes
  `https://locklane.javalib.dev` alongside the existing localhost entries
- `grep -n "allowed-origins" engine/src/main/resources/application.yml` shows the new
  origin present

## Explicitly not
- Documenting the reverse-proxy setup itself, or verifying the iOS Safari home-screen
  install — those are the other two done-when criteria of #223 and are out of scope
  here.

## Decisions made along the way
- none

## Deviations / notes
- none
