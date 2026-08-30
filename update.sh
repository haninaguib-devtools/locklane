#!/usr/bin/env bash
# Pulls the current locklane.jar (latest build) into this directory and relaunches it
# (#289). Run from ~/.locklane, where install.sh put things.
#
# The one edit it ever makes to application-locklane.properties is retiring the stored
# bootstrap password (#384) — see below. Nothing else in that file is touched.
set -euo pipefail

# Mirrors engine/src/main/resources/application.yml's
# locklane.release-check.repository — the only release channel that exists today.
REPO="haninaguib-devtools/locklane"

cd "$(dirname "$0")"

if ! command -v gh >/dev/null 2>&1; then
  echo "error: the GitHub CLI (gh) is required — install it from https://cli.github.com, then run 'gh auth login'." >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "error: gh is not logged in — run 'gh auth login' first." >&2
  exit 1
fi

# --- Retire a stored bootstrap password ---------------------------------------
# Installs predating #384 left the login password in plaintext here. The engine
# consumed it on its very first start and has ignored it ever since, so the value is
# dead — drop that one line and leave every other byte of the file as it was.

props_file="application-locklane.properties"
if [ -f "$props_file" ] && grep -Eq '^locklane\.security\.bootstrap-password[[:space:]]*=' "$props_file"; then
  echo "Removing the stored bootstrap password from $props_file..."
  tmp_props="$(mktemp "$props_file.XXXXXX")"
  chmod 600 "$tmp_props"
  sed -E '/^locklane\.security\.bootstrap-password[[:space:]]*=/d' "$props_file" > "$tmp_props"
  mv "$tmp_props" "$props_file"
fi

echo "Downloading locklane.jar (latest build of $REPO)..."
gh release download latest --repo "$REPO" --pattern locklane.jar \
  --dir . --clobber

echo "Relaunching..."
exec java -jar locklane.jar
