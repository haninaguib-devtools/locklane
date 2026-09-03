# GitHub token lifetime

**Status:** accepted design (#620), implemented (#656): the engine persists the token
pair, renews ahead of expiry and once on a 401, evicts each affected project's `gh`
client, and shows an account it cannot renew as needing reconnection.

## What a person sees

Connect a GitHub account through Settings → GitHub accounts → "Sign in with GitHub".
Everything works. Then, some time later, every project on that account shows stale
data and the engine log fills with `HTTP 401: Bad credentials` on every 30-second
refresh, until someone removes the account and connects it again — which only buys
the same interval once more.

## Cause

**GitHub now issues short-lived, refreshable tokens to OAuth Apps by default, and the
engine keeps only the access token.**

Evidence, in order of weight:

1. GitHub's changelog of **2026-08-14**, "Multiple redirect URIs and token refresh for
   OAuth apps": OAuth Apps gained the same short-lived-token pattern GitHub Apps have
   had — "an access token that lives for eight hours and a refresh token that's valid
   for six months" — and, decisively, **"Short-lived tokens are enabled by default for
   all new applications."** An app opts out under its registration's optional
   features; an existing app opts in per sign-in by requesting the `offline_access`
   scope, or globally in its settings.
2. The "Locklane" OAuth App whose client id the engine ships
   (`locklane.github.oauth-client-id`, an `Ov23…` OAuth App id — confirmed an OAuth
   App, not a GitHub App, on the owner's "Authorized OAuth Apps" page) was registered
   for #590, merged **2026-09-02** — after that default flipped. It is therefore a
   short-lived-token app unless someone opted it out.
3. GitHub's current OAuth docs ("Authorizing OAuth apps" → Device flow) list the
   fields such an app receives on the token endpoint: `access_token`, `token_type`,
   `scope`, **and** `expires_in`, `refresh_token`, `refresh_token_expires_in` "when
   your OAuth app uses expiring access tokens". A non-expiring app receives only the
   first three.
4. The engine reads only `access_token` (`HttpGhDeviceFlow.poll`, before this task),
   stores it encrypted (`GhAccountsService.completeFlow` → `github_accounts.token`),
   and never looks at it again. Nothing renews it; nothing notices the 401 beyond
   logging it. The two failures in the issue are exactly this shape: each account
   died a fixed interval after its own creation, independently of the other, and
   only device-flow accounts died — the two pasted-token accounts on the development
   install (`gho_` tokens from `gh auth`, no expiry) have run for days without a
   single `Bad credentials`.

**What is documented as eight hours was observed as sixty minutes.** The docs and the
changelog both say eight hours; the issue's log shows both accounts failing at
+60 minutes. The value GitHub actually sends is the one that matters, and this task
makes the engine record it: `HttpGhDeviceFlow` now parses the whole token response,
and `GhAccountsService` logs, on every completed sign-in, a redacted line —

```
Device-flow token response for user 1: token_type=bearer scope=… expires_in=<n> refresh_token=present refresh_token_expires_in=<n>
```

— followed by a WARN when `expires_in` is set, naming the seconds until the token
dies. The next device-flow sign-in on any install produces the exact figure, values
of the secrets never logged. Whether it reads 3600 or 28800 does not change the fix
below; it only sets the renewal margin.

Ruled out:

- **Revocation by the ten-tokens-per-user/app/scope cap.** GitHub revokes the oldest
  past ten; the issue's accounts were each the first token for their user. A cap
  revocation would also not fire on a fixed timer from creation.
- **Org OAuth-App access restrictions.** Those answer 403 with a policy message, not
  401 `Bad credentials`, and they apply at first use, not an hour later.
- **A second device flow for the same user.** The two accounts are different GitHub
  users and died on their own clocks.
- **Anything in the engine's own scheduling.** Nothing runs on an hourly timer
  against the accounts table; the only scheduled work near this code is
  `ProjectGhResources.refreshAll` every 30 seconds, which is where the 401s surface.

## Fix

Treat a device-flow token the way GitHub now defines it: a pair, one short-lived and
one long-lived, renewed before the short one dies and again on any 401.

1. **Persist the pair.** `github_accounts` gains `refresh_token` (encrypted by
   `TokenCipher` exactly like `token`, never returned by any controller, never
   logged), `token_expires_at` and `refresh_token_expires_at` (ISO-8601 text, like
   `created_at`). All three nullable: a pasted-token account (#550,
   `GhAccountsService.addByToken`) has none and is never renewed. **This is a Flyway
   migration**, which is why the implementation is split out of #620 rather than
   riding in it (the issue's own Goal).
2. **Renew ahead of expiry.** A scheduled pass over accounts with a
   `token_expires_at` within a margin (five minutes, well inside any plausible
   lifetime) posts `grant_type=refresh_token` to `https://github.com/login/oauth/access_token`
   with `client_id` and `refresh_token` — a new `GhDeviceFlow.refresh(clientId,
   refreshToken)` next to `start`/`poll`, returning the same `PollResult.Success`
   shape — and stores the new pair. GitHub rotates the refresh token on every use; the
   old one is dead the moment the new one is stored.
3. **Renew on 401 too.** When a project's `gh` call fails with `Bad credentials` and
   its account carries a refresh token, renew once immediately and retry; a second
   401 marks the account as needing re-connection and stops retrying.
4. **Push the new token to `gh` without a restart.** After every renewal, call
   `ProjectGhResources.evict(projectId)` for every project referencing that account
   (`ProjectRepository.findNamesReferencingGithubAccount` already knows the set;
   the follow-up adds an id-returning twin), so the next lookup rebuilds the
   `CliGhClient` with the fresh token.
5. **Show it.** An account whose refresh failed, or whose refresh token is itself
   expired (six months idle), is listed on the accounts page as needing reconnection
   rather than silently failing. The client-side wording belongs to #619's status
   surface; this design only requires the state to exist on the account.

**Tests** (fake `GhDeviceFlow`, no network): a short-lived token is stored with its
expiry; the renewal pass renews only accounts inside the margin, stores the rotated
pair, and evicts each of the account's projects exactly once; a 401 triggers one
renewal and one retry; a pasted-token account is never touched by any of it.

**Not chosen:**

- *Opt the app out of short-lived tokens in its GitHub settings.* One click, no
  code — and every fork or self-registered app (`application.yml` documents both)
  would hit the same wall, since the default is now short-lived. The engine has to
  handle the shape GitHub sends by default. The owner may still flip the switch as
  an interim measure while the follow-up lands; it changes nothing in this design.
- *Ask for `offline_access` explicitly.* Redundant for an app already on short-lived
  tokens, and it would force expiry onto a self-registered app that opted out.
- *Re-run the device flow on 401.* Needs a human at a browser every eight hours.

## Where it landed (#656)

The migration is `V17__AddRefreshTokenToGithubAccounts` (it also adds
`renewal_failed_at`, the mark behind "needs reconnection"); `GhDeviceFlow.refresh` and
its `HttpGhDeviceFlow` implementation post the refresh grant; `GhTokenRenewalService`
is both the scheduled pass (`locklane.github.token-renewal.interval-ms`, once a minute,
five-minute margin) and the on-401 renewal, sharing one lock per account because GitHub
rotates the refresh token on every use; `ProjectGhResources.refreshAll` is the 401 hook,
retrying once with a rebuilt context and marking the account when the retry is refused
too. The human check remains the issue's own: connect via the device flow, wait past
the logged `expires_in`, confirm no `Bad credentials` and a sidenav that still updates.
