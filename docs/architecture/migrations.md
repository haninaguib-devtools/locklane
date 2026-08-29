# Migrations

**Status:** binding convention. No migration files exist yet — the first is written
when the first breaking change to a template-owned file actually happens (issue #20's
own Non-goals); this page fixes the shape in advance so that first one has somewhere
to land.

A migration exists only for a **breaking** template change — one where copying the new
file over the old one and re-applying local slots (the ordinary `t-update` sync,
`docs/architecture/manifest.md`) is not enough, because something about the change
needs the consumer's own state to be touched: a renamed operation a consumer's own
scripts might call, a moved section a local cross-reference might point at, a file that
no longer exists. Most template updates need no migration at all; the plain sync
handles them.

## Where they live

One append-only file per breaking change, in `migrations/`, named `V<n>__<slug>.md`
where `<n>` is a strictly increasing integer (no gaps required, just increasing) and
`<slug>` is a short kebab-case description. Files are never edited after they ship —
only added — and never renumbered, so a consumer's `migrations_applied` field
(`docs/architecture/manifest.md`) always means the same thing regardless of when it was
last read.

## Shape

```markdown
# V<n> — <short title>

**Introduced:** <tag this first shipped in>

## What broke

<one paragraph: what changed, and why a plain file sync isn't enough on its own>

## Instructions for the upgrading agent

<concrete, followable steps t-update (or whoever is applying the migration) carries
out in the consumer repo — not prose a human has to interpret at merge time>

## Done-when

<a check the agent (or the consumer's own CI, once t-update has landed the change)
can run to confirm the migration actually applied — a command, a grep, an exit code>
```

## How `t-update` applies them

At each sync, `t-update` reads every `V<n>__*.md` in the fetched target tag's
`migrations/`, applies every one with `<n>` greater than the manifest's current
`migrations_applied` and less than or equal to the target tag's own highest `<n>`, in
increasing order, then writes the new highest applied `<n>` back into the manifest. A
migration's own Done-when is checked immediately after it applies, before the next one
runs — a migration whose Done-when fails stops the whole update where it is, the same
way `t-update` stops on a dirty owned file: reported, not worked around.
