#!/usr/bin/env bash
# Installs locklane into ~/.locklane (#289, #678). This file is only the bootstrap: it
# fetches the control program -- `locklane`, the one file that owns installing,
# updating, starting, stopping and uninstalling -- into the install directory and hands
# the whole run to `locklane install`. A fresh install asks for a port and a login,
# creates the account, and registers the server as a per-user service; a machine that
# already has locklane is updated instead, with its settings kept (#675).
#
# Usage: curl -fsSL <url-to-this-file> | bash
set -euo pipefail

# Mirrors engine/src/main/resources/application.yml's
# locklane.release-check.repository -- the repo whose newest permanent release the
# in-app update banner announces; this installs from that same channel (#465).
REPO="haninaguib-devtools/locklane"
INSTALL_DIR="${LOCKLANE_HOME:-$HOME/.locklane}"

# gh does not need to be logged in on this host (#551, #610): `gh release download`
# works unauthenticated on a public repository, and the fallback below is a plain raw
# file. gh itself still has to be installed -- the server runs it for every project.
if ! command -v gh >/dev/null 2>&1; then
  echo "error: the GitHub CLI (gh) is required — install it from https://cli.github.com." >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR"

# The control program comes from the same release as the jar it will install. A
# release cut before it existed carries no such asset: then the copy on main, over the
# same unauthenticated raw-file channel the README's one-liner uses for this script.
echo "Fetching the locklane control program..."
if ! gh release download --repo "$REPO" --pattern locklane \
    --output "$INSTALL_DIR/locklane" --clobber 2>/dev/null; then
  curl -fsSL "https://raw.githubusercontent.com/$REPO/main/scripts/locklane" \
    > "$INSTALL_DIR/locklane"
fi
chmod +x "$INSTALL_DIR/locklane"

# exec, so nothing below this line exists to run; stdin from /dev/null, so under
# `curl | bash` nothing downstream can read the rest of this script off the pipe
# (#354) -- every prompt in `locklane install` reads /dev/tty.
exec "$INSTALL_DIR/locklane" install < /dev/null
