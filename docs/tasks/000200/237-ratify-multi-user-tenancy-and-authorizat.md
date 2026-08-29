# 237 — Ratify multi-user tenancy and authorization model (ADR)
Issue: #237 · Part of: #236

## Asked
Before any schema or authorization code changes land for multi-user support, write
down and ratify the design this initiative implements, as an ADR. The ADR must state,
with rationale and alternatives considered:

- A project's owner (`owner_user_id`) is the isolation boundary, enforced in the
  application/query layer — never by filesystem location alone.
- Workareas move from `workareas/<project-slug>` to `workareas/<user_id>/<project-slug>`,
  for on-disk organization only, not as the security boundary.
- The first account ever bootstrapped becomes the admin. There is no self-registration —
  only an admin creates accounts.
- Deleting a user cascade-deletes everything only they had: their owned projects, those
  projects' on-disk workarea checkouts, and any worktree/console sessions tied to them.
- An admin-created account must set a new password on first login before it can use the
  app.
- Worktree/console session visibility is derived from the owning project's owner (or
  admin), replacing today's first-attach-claims-it model on `worktree_sessions`.

## Done when
- A new `docs/adr/00N-*.md` file is merged, following the existing ADR template and
  numbering, stating the six decisions above plus alternatives considered and revisit
  triggers.
- `CONSTITUTION.md`'s reserved application-surfaces line under §3 is updated to name the
  concrete paths this ADR's decisions make protected.

## Explicitly not
- Implementing any of the ratified decisions — that is #238, #239, #240, #241, #242 (per
  the issue's Non-goals). `engine/**` and `client/**` stay untouched by this task.

## Decisions made along the way
- ADR numbered **007**, not the 003 the issue's Plan names (t-work session, 2026-08-29).
  The Plan's Allowed paths say `docs/adr/003-*.md`, reasoning "next unused ADR number;
  existing are 000-template, 001, 002" — that was true when `/t-plan` ran, but this
  branch (built on `wip/236-integration`, off current `main`) already carries
  `docs/adr/003-native-sub-issues-and-dependencies.md` through
  `docs/adr/006-automatic-worktree-cleanup-sweep.md`, merged by unrelated tasks since
  the plan was written. Reusing `003` would collide with an existing, unrelated ADR
  (the repo already has one accidental duplicate — two files both numbered `002` — which
  is a pre-existing defect, not a precedent worth repeating). The binding intent stated
  in the plan's own parenthetical is "next unused ADR number," so this task applies that
  rule against the branch's actual current state and uses `007`, the first number with no
  file. Flagged here for `/t-review` and for whoever works #192's template-sync
  overlap this plan already called out.
- Named the file `docs/adr/007-multi-user-tenancy-and-authorization.md` — the plan's
  Allowed-paths pattern (`docs/adr/003-*.md`, read as "docs/adr/<the number>-*.md") still
  matches under the corrected number.
- `CONSTITUTION.md` §3: first tried converting the reserved placeholder sentence into
  real `- ` bullets (matching the surrounding protected-surfaces list's format), naming
  the concrete migration directories, `ProjectController`/`ProjectRepository`, and
  `EngineUserDetailsService`/`SecurityConfig` plus the future admin user-management
  controller — the exact paths were left to this task's judgment by the plan's Risks
  section, made against ADR-007's actual decisions once written.
  `./.t-workflow/scripts/consistency-check.sh`'s check 9 then correctly failed: it
  requires every backticked path named in §3's actual bullet list to already have a
  matching pattern in `.t-workflow/scripts/protected-paths.sh` ("one rule in two
  forms"), and none of these six code paths carry the ratified `owner_user_id`
  checks yet — `protected-paths.sh` is also not in this task's Allowed paths, so
  wiring it up here would be scope growth onto a second protected surface the plan
  never named. Fixed by keeping the reserved line in its original non-bulleted
  parenthetical paragraph form (excluded from check 9's §3 bullet-scan, same as the
  placeholder it replaces) while still naming the same concrete paths in prose, and
  saying explicitly that #238-#242 adds each surface's bullet and protected-paths.sh
  pattern together when its code actually lands. This satisfies done-when #2 ("name
  the concrete paths") without falsely claiming enforcement that doesn't exist yet.

## Deviations / notes
- ADR number 003 → 007, per above — the only deviation from the plan's literal text;
  the plan's own stated rationale ("next unused ADR number") is what 007 satisfies. No
  other Allowed-path, Non-goal, or Scope line was touched differently than planned.
- The plan's agent_checks reference the literal glob `docs/adr/003-*.md`; run against
  the actual file (`docs/adr/007-*.md`) instead, with results reported verbatim in the
  PR. See the check log below.
- The §3 reserved-line replacement went through one false start (real bullets, caught
  by consistency-check's check 9) before landing on the non-bulleted prose form — see
  "Decisions made along the way" above for the full reasoning. No scope was widened:
  `.t-workflow/scripts/protected-paths.sh` was read to understand the failure but never
  edited.
- Per the plan's Risks section, flagging again — now with mechanized confirmation, not
  just a hypothetical: `.t-workflow/scripts/check-manifest.sh` **fails** on this diff
  (`DRIFT: CONSTITUTION.md`), because this repo carries a real `.template-manifest.json`
  (it is a pinned consumer of `haninaguib-devtools/t-workflow`) and §3's reserved
  application-surfaces line is *not* one of the two places
  `docs/architecture/local-slots.md` documents as per-repo (`CONSTITUTION.md` §4 and
  `AGENTS.md` §Checks item 1) — every other line in `CONSTITUTION.md` is template-owned
  and expected to stay byte-identical to the pinned release. Filling in that reserved
  line at all — which is exactly what its own placeholder text and this issue's Goal
  ask for — therefore always registers as manifest drift under the current template,
  regardless of wording or format. Confirmed the baseline has zero drift before this
  change (`git stash` + rerun) and that neither `check-manifest.sh` nor
  `docs/architecture/local-slots.md` nor `.template-manifest.json` was edited by this
  task — none are in the Plan's Allowed paths, and `docs/architecture/manifest.md`
  states `/t-update` is the only thing meant to write the manifest. **This is a
  template gap, not a mistake in this ADR or in the §3 wording**: `local-slots.md`
  should arguably mark §3's reserved line as a slot the same way it already marks §4,
  since both exist for a consumer to fill in once its own application/stack decisions
  are ratified. Left for the human to decide: refresh `.template-manifest.json`'s
  `CONSTITUTION.md` hash out-of-band (or via `/t-update`) before this PR's CI can go
  green, and/or raise the local-slots gap with the upstream template
  (`haninaguib-devtools/t-workflow`). Same underlying tension as the #192 sequencing
  risk already flagged in the plan — both point at the same fix.
- No other deviations.
