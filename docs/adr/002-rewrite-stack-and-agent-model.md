# ADR-002: The rewrite's stack and agent model

**Status:** Accepted · 2026-08-24
**Deciders:** project owner *(solo phase; see ADR-001's Deciders note — the same
caveat applies here)*

## Context

Initiative #1 rebuilds the locklane app in this repo, using the old locklane app (a
separate repository) as a reference for patterns, not as code to copy. Before any
application code lands, the stack itself needs to be a durable decision rather than
something that lives only in chat and issue bodies — `CONSTITUTION.md` §1.3 rules out
anything binding existing only in an issue or PR thread, and §4 ("Stack &
architecture") is reserved specifically for this.

Two requirements shaped the choice, both non-negotiable for how the app is used: an
agent must keep running after the laptop that started it is closed, and it must be
reachable from any browser, from anywhere — the target deployment is a home server the
client connects to remotely.

## Decision

Stack: Spring Boot engine + Angular PWA client + SQLite for durable non-binding state
— unchanged from the old locklane app.

Agent model: one persistent PTY (pseudo-terminal) process per git worktree, not a job
queue — closer to browser-based tmux than to fire-and-forget task execution. A browser
client attaches to a worktree's live session over the network and can detach and
reattach from anywhere without killing the underlying process.

## Rationale

- The two requirements in Context — survive the laptop closing, reachable from any
  browser anywhere — both require a long-running server process independent of any
  single client. That rules out any architecture where the agent's process lives only
  inside a client application.
- A persistent PTY per worktree, reattachable over the network, is the most direct fit
  for both requirements: the terminal keeps running on the server regardless of which,
  or whether any, browser is currently attached.
- Reusing the old app's stack avoids re-deriving already-working patterns (PTY wiring,
  xterm.js integration, SQLite schema shape) for a rewrite whose actual goal is a
  UI and performance restructure, not a new architecture (initiative #1's Goal).

## Alternatives considered

- **Tauri** (native PTY access, no server process) — rejected. Tauri ties the running
  process to a desktop app instance: it cannot keep an agent running once that desktop
  app, and the laptop it runs on, closes, and it has no natural "reachable from any
  browser, from anywhere" story. Both are hard requirements here, not preferences a
  simpler architecture could trade away.
- **A job-queue model** (submit work, poll for a result) — rejected. A queue fits
  fire-and-forget tasks; it does not fit an interactive, continuously-running terminal
  session a person wants to watch and type into live, which is what the app needs.

## Consequences / revisit triggers

Accepted knowingly: the server is a single point of failure for every running agent
session, and there is no offline/local-only mode — reachability from anywhere requires
a server to reach.

Any of these reopens this decision, as a new ADR:

1. **The home-server-hosting requirement is dropped** — e.g. a shift to a model where
   only local execution matters. Desktop-only alternatives (Tauri) become viable again
   once "reachable from anywhere" is no longer a hard requirement.
2. **A second maintainer joins** and the operational cost of running and maintaining
   the server component is found to outweigh its reachability benefit.
3. **The persistent-PTY-per-worktree model does not scale** — e.g. many concurrent
   worktrees exhausting server resources — in which case a hybrid or queue-backed model
   for idle sessions is worth reconsidering.
