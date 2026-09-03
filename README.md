# locklane

<!-- Replace this line with one sentence saying what this project is. -->

Generated from t-workflow @ 4b8ad51 — https://github.com/haninaguib-devtools/t-workflow

## Installing

Install locklane on your own machine — this downloads the engine, asks for a port and a
login, and leaves it running as a per-user service:

```
curl -fsSL https://raw.githubusercontent.com/haninaguib-devtools/locklane/main/install.sh | bash
```

Everything lands in `~/.locklane`. Five scripts live there afterwards, and each one
already knows how the server was installed — as a systemd user service, a launchd agent,
or a plain detached process — so you never need to:

- `~/.locklane/status.sh` — say whether the server is running (exit 0) or not (non-zero).
- `~/.locklane/stop.sh` — stop the server. On macOS this also unloads the launchd agent,
  so it stays down across logins until `start.sh` brings it back.
- `~/.locklane/start.sh` — start it again. `stop.sh && start.sh` is the restart; there is
  no separate restart script.
- `~/.locklane/update.sh` — pull a newer build and restart the server.
- `~/.locklane/uninstall.sh` — stop the server and de-register it from the service
  manager, then ask separately whether to delete `~/.locklane` itself. That directory
  holds the login accounts, the projects, and the database, so deleting it needs a typed
  confirmation and is never the default.

Locklane acts on GitHub through the accounts you sign in to it, not through the host's
own `gh` login: open the GitHub accounts page (the account menu in the top right, once
you're signed in to Locklane itself) and sign in there — through GitHub's device flow,
or by pasting a token — before importing or creating a project. Either way the account
needs the `repo` scope, and the `workflow` scope too if you create projects bootstrapped
with t-workflow: their first push carries `.github/workflows/ci.yml`, and GitHub refuses
that push from a token without `workflow`. The accounts page shows whether an account has
`workflow` before you pick it for a bootstrap.

Project templates live on the host too, one directory per template at
`~/.locklane/templates/<name>/template.md`, where `<name>` is lowercase letters, digits
and dashes. The file opens with a YAML frontmatter block carrying `title` (what the Add
Project dialog's template pull-down shows) and `description` (one line, shown as that
option's hint); everything after the frontmatter is the template itself — plain
markdown describing how that kind of project should be built, which the engine commits
as `PROJECT_TEMPLATE.md` into a project created from it and never runs. A few built-in
templates ship inside the engine; a host directory with the same `<name>` as a built-in
replaces it in the listing.

## Read first

- `CONSTITUTION.md` — the invariants. Binding on every task.
- `AGENTS.md` — what an agent reads on session start: the pipeline, the conventions,
  and the check set.
- `docs/workflow.md` — how any change moves from idea to `main`.

## Bootstrapping

Genesis has already happened: the installer filled the project name, made the first
commit, and — if a remote was created — pushed it and applied the repository settings.
Under `CONSTITUTION.md` §3 the genesis exception ends at that pushed commit, so from
here on **every** change to the tree goes through the pipeline, starting with `/t-open`.

Two placeholders remain, because no installer can know them. They are the last things
that may be filled in by hand only if you have not yet pushed; after the push they are
ordinary pipeline work like anything else:

1. **`CONSTITUTION.md` §4** — the stack and architecture constraints this project
   commits to, each one a single line pointing at the ADR in `docs/adr/` that ratified
   it. Until an ADR exists, the section stays reserved and nothing may assume more than
   it says.
2. **`AGENTS.md` §Checks** — the project's build and test command. That section is the
   only place the workflow reads it from. Name it there first, then add the same command
   to `.github/workflows/ci.yml` as a third job alongside `consistency` and `record`.

If the installer did not create a remote repository, create one and apply the settings:

```
gh repo create <name> --private --source . --remote origin --push
./scripts/github-bootstrap.sh
```

Re-run `./scripts/github-bootstrap.sh` once CI has run on `main` — that is when the
status checks can be marked required.

## Licence

No LICENSE file was created for this project. A project with no licence is "all rights
reserved" by default, which is the safe place to start. Add the licence you want before
publishing anything.

The delivery system this project was generated from is MIT-licensed; that covers the
template, not your project's own code.

## The pipeline

Work starts from a tracker issue and reaches `main` only by a pull request a human
confirmed. Everything between those two points is chosen per task.

| Skill | Stage |
|---|---|
| `/t-open` | Conversation to issue(s). How all work starts. |
| `/t-plan` | Pins scope, risks, and validation onto the issue. Required before a protected surface changes. |
| `/t-work` | Branch, record, implement, check, draft pull request. |
| `/t-review` | Cold-context review, posted on the pull request. Required before shipping a protected surface. |
| `/t-drive` | Optional. Walks an initiative's children to completion on an integration branch, then stops once for a human-confirmed pull request to `main`. |
| `/t-ship` | Human-confirmed squash merge. |
| `/t-cancel` | Terminal exit: reason recorded, neighbours decided, then the pull request closed and its branch deleted. |
| `/t-update` | For a repo generated from this template. Syncs its template-owned files to a pinned release. |
| `/t-status` | Read-only pipeline overview. |

`AGENTS.md` is the full contract and the one an agent reads on session start. This table
is a map, not a substitute for it.

## Notes

The workflow is stack-agnostic: nothing in the skills assumes a language or framework.
It does assume the trunk is called `main` — that name is written literally in the skills
and scripts, so a repository on `master` or `trunk` renames it there first (one task, one
find-and-replace across the protected surfaces).

The tracker and the forge are pluggable: `docs/adapters/TRACKER.md` and
`docs/adapters/FORGE.md` map every issue and pull-request operation to concrete commands.
GitHub is the default; swapping in another backend means editing those two files only.
