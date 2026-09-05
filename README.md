# locklane

<!-- Replace this line with one sentence saying what this project is. -->

Generated from t-workflow @ 4b8ad51 — https://github.com/haninaguib-devtools/t-workflow

## Installing

Install locklane on your own machine — this downloads the engine, asks for a port and a
login, and leaves it running as a per-user service:

```
curl -fsSL https://raw.githubusercontent.com/haninaguib-devtools/locklane/main/install.sh | bash
```

Everything lands in `~/.locklane`, and one program there runs the whole lifecycle:
`~/.locklane/locklane`. It already knows how the server was installed — as a systemd
user service on Linux or a launchd agent on macOS — so you never need to. The macOS
agent is registered as a Background-session agent, so installing, starting and
stopping it works from an SSH session with nobody logged in at the screen (a Mac that
has not had any login since it booted is untested). Run it from a
terminal outside Locklane's own console (ssh, or a local terminal): a console tab is a
child of the server, so a command that stops the server from there would take itself
down with it, and the commands that stop it refuse to start from a console tab (or the
IDE terminal opened from one) for that reason.

- `locklane status` — say whether the server is running (exit 0) or not (non-zero).
- `locklane stop` — stop the server and check that it is gone: it asks the service
  manager to stop, waits, forces the server and anything it spawned if it has to, and
  says "stopped" only once nothing is left. On macOS this also unloads the launchd
  agent; macOS loads it again at your next login, and `locklane start` brings it back
  before then.
- `locklane start` — start it again. `locklane restart` is stop then start.
- `locklane update` — pull a newer build and restart the server on it. It first
  refreshes itself from the newest release, downloads and checks the new jar while the
  old server keeps running, and only then stops, swaps and restarts, printing the
  version it installed. Re-running the install one-liner does the same.
- `locklane uninstall` — stop the server (verified, or nothing is removed) and
  de-register it from the service manager, then ask separately whether to delete
  `~/.locklane` itself. That directory holds the login accounts, the projects, and the
  database, so deleting it needs a typed confirmation and is never the default.
- `locklane register` — rewrite the service registration with your current login
  PATH and restart: run it after editing `application-locklane.properties`, or when a
  CLI the server needs is newly on your PATH.

An install made before v0.2.10 moves onto this layout by itself the first time its own
`update.sh` (or the install one-liner) runs; one older than v0.2.8 needs the one-liner.

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
