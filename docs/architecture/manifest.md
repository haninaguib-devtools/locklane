# The update manifest

**Status:** binding convention.

How a consumer repo pins a template release and verifies it hasn't silently drifted.
`t-update` (`.claude/skills/t-update/SKILL.md`) is the one thing that writes a
manifest; `.t-workflow/scripts/check-manifest.sh` is the one thing that verifies it — a consumer's
own CI runs the check, this repo does not (it is the template, not a pinned consumer,
and carries no manifest of its own).

## Release versioning

This repo's releases are git tags, `v1`, `v2`, … on `main`, cut by hand after a PR
that's meant to be a sync point merges. A consumer always pins a tag; it never tracks
`main` or "latest" — the tag *is* the unit `t-update` moves between.

## The manifest file

`.template-manifest.json`, committed at a consumer repo's root:

```json
{
  "template": "haninaguib-devtools/t-workflow",
  "tag": "v1",
  "migrations_applied": 0,
  "files": {
    "AGENTS.md": "<sha256 hex>",
    "CONSTITUTION.md": "<sha256 hex>",
    "...": "..."
  }
}
```

- `template` — the template repo, `owner/name`. First adoption reads this from the
  consumer's own `README.md` (`installer/bootstrap.sh` stamps
  `Generated from t-workflow @ <ref> — <url>` there at genesis) if no manifest exists
  yet and none was given explicitly.
- `tag` — the template release this consumer is currently synced to.
- `migrations_applied` — the highest migration number (`docs/architecture/migrations.md`)
  already applied; `t-update` applies only what's greater than this and at or below the
  target tag.
- `files` — every template-owned path at that tag, each mapped to its **normalized**
  hash (below) at that tag. This is the set the CI lock enforces; nothing outside it is
  ever touched by a sync, and a consumer's own files (its own `docs/architecture/`
  additions, its own `CONSTITUTION.md` §4 content, anything not listed) are never
  compared against anything.

## Which files are template-owned

Not simply every protected path (`CONSTITUTION.md` §3). Some protected paths are
genesis-only: stamped once by `installer/bootstrap.sh` and then wholly the consumer's
own (`README.md`), or deleted outright for every generated project (`LICENSE`,
`installer/`, `site/`, `.github/workflows/installer.yml`,
`.github/workflows/pages.yml`) — there is nothing in a consumer repo for those paths to
sync *to*. Nor is pattern matching alone sufficient once a consumer's own repo exists: a
consumer-authored file can legitimately sit under a protected directory it did not get
from the template — a consumer-local skill under `.claude/skills/` (the non-`t-*`
convention `AGENTS.md` names) or a consumer's own ADR under `docs/adr/` — and pattern
matching cannot tell that apart from a file the template actually shipped.

`.t-workflow/scripts/template-owned-paths.sh --list` is the executable list, computed in two
tiers:

1. **Pattern match**: every tracked file matching a protected pattern, minus the
   genesis-only exclusion set above — the same computation as before.
2. **Manifest narrowing**: when `.template-manifest.json` exists at the repo root
   (a pinned consumer), the list narrows to the paths that are *also* a key in that
   manifest's `files` map — the manifest is the authoritative record of what the
   consumer's pinned tag actually shipped, so a pattern match outside it is a
   consumer-authored file, never template-owned. With no manifest present — this repo
   itself (never a pinned consumer), a `t-update` scratch clone of the template at a
   target tag (which never carries a manifest of its own), or a freshly-bootstrapped
   consumer before its first commit — the list is the pattern match alone, since there
   is no manifest yet to narrow against.

The script is itself template-owned, so a future exclusion, inclusion, or narrowing
change reaches consumers the same way any other template fix does.

## Normalized hashing

A template-owned file may carry `<!-- local -->` … `<!-- /local -->` regions
(`docs/architecture/local-slots.md`) that are the consumer's own content by design —
those must never register as drift. Before hashing, every line strictly between a
marker pair (the markers themselves stay) is stripped; the sha256 of what's left is
what the manifest records and what `check-manifest.sh` recomputes. A file with no
markers hashes as-is. `.t-workflow/scripts/check-manifest.sh --hash-file <path>` is the one
implementation of this rule — `t-update` (writing the manifest) and
`.t-workflow/scripts/check-manifest.sh`'s own verify mode (reading it back) both call it, so the two
can never compute the hash two different ways.

`CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, and `.agents/skills` are
symlinks (`docs/architecture/local-slots.md` §Alias mechanism), one of them (`.agents/skills`)
pointing at a directory — nothing content-hashable. Both `--hash-file` and verify mode
detect a symlink first and record/compare its **target** (`symlink:<target>`), never
its resolved content; a consumer whose alias somehow diverged (a real file or directory
instead of a symlink, or a symlink pointing somewhere else) is caught the same way any
other drift is. `t-update` syncs each by recreating the same symlink, never by writing
separate content.

## The CI lock

`.t-workflow/scripts/check-manifest.sh`, no arguments: reads `.template-manifest.json` at the repo
root, recomputes every listed file's normalized hash from the working tree, and fails
(exit 1) listing every path that drifted or went missing. A consumer wires this into
its own CI as a required check — this repo's `.github/workflows/ci.yml` does not run it,
since running it here would just report "no manifest," which is expected, not a
failure.
