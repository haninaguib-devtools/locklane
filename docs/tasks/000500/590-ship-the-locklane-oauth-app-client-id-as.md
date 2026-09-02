# 590 — Ship the Locklane OAuth App client id as the engine's default so GitHub sign-in works on every install
Issue: #590

## Asked
Make "Sign in with GitHub" on the GitHub accounts page work on every Locklane install
out of the box. Today the button only works after the operator sets
`locklane.github.oauth-client-id` by hand on each host (#550's recorded manual step),
and forgetting it leaves the accounts page saying "device sign-in isn't set up on this
host". The Locklane OAuth App is now registered on GitHub with device flow enabled; its
client id is `Ov23lifzJ18NqTqgqdRn`. Ship that id as the engine's built-in default so a
plain install signs in through device flow with no extra configuration, while still
letting a host override it (a fork or a self-registered app) through the existing
property / `LOCKLANE_GITHUB_OAUTH_CLIENT_ID` env var.

An OAuth App client id is public by design (it is sent in the clear to GitHub's
device-code endpoint and shown in every authorization URL), so committing it is safe;
no client secret exists or is needed for device flow.

## Done when
- `grep -n 'oauth-client-id: "Ov23lifzJ18NqTqgqdRn"' engine/src/main/resources/application.yml`
  matches, and the comment above the key says the shipped default is the registered
  "Locklane" OAuth App and how to override it.
- An engine started with no `locklane.github.oauth-client-id` override answers
  `POST /api/github/accounts/device/start` with a real device code, not 501 — covered
  by a unit test asserting `GhAccountsService` sees a non-blank client id under the
  default configuration (or equivalent), so the default cannot silently regress to
  blank.
- Setting `LOCKLANE_GITHUB_OAUTH_CLIENT_ID` (or the property in
  `~/.locklane/application-locklane.properties`) still takes precedence over the
  built-in default — existing behaviour, unchanged.
- `./mvnw -B test` passes.

## Explicitly not
- Registering or changing the OAuth App on GitHub itself (already done by hand).
- Any change to the device-flow implementation, the requested scopes, or the accounts
  page UI.
- Changes to `install.sh`, `README.md`, or the installer: the default lives in the
  jar, so nothing on the host needs to know about it.

## Decisions made along the way
- The regression test reads the shipped `application.yml` off the classpath with
  Spring's `YamlPropertiesFactoryBean` and feeds that value into `GhAccountsService`'s
  test constructor with a fake `GhDeviceFlow`, rather than booting a `@SpringBootTest`
  context: the service constructs its real `HttpGhDeviceFlow` itself (not a bean), so
  a context test calling `startDeviceFlow` would reach github.com, and a context test
  would also silently pass or fail on whatever `LOCKLANE_GITHUB_OAUTH_CLIENT_ID` the
  developer's own shell carries. Reading the file directly pins exactly the artifact
  the issue's Done-when names (agent, 2026-09-02).

## Deviations / notes
- `./mvnw -B test` was run with `GH_TOKEN` and `GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_0`/
  `GIT_CONFIG_VALUE_0` unset: this task ran inside a Locklane console session, which
  injects a GitHub token and a `credential.helper` override into the shell, and four
  pre-existing tests (`ProjectCheckoutServiceTest`'s two "without an account" cases and
  `ProjectConsoleWebSocketIntegrationTest`'s "no stored token gets no GH_TOKEN" case)
  assert an ambient-free environment and fail under those variables regardless of this
  change. With them unset the whole suite (763 tests) passes. Out of scope here —
  proposed as its own issue: make those tests scrub the ambient `GH_TOKEN` and
  `GIT_CONFIG_*` variables from the processes they spawn, so the suite passes from
  inside a Locklane console (agent, 2026-09-02).
- The test classpath's own `src/test/resources/application.yml` replaces (does not
  merge with) the shipped one, and does not set `locklane.github.oauth-client-id` —
  so `@SpringBootTest` contexts still see a blank id, which is the same as before
  this task and is why the new test reads the shipped file directly.
