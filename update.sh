#!/usr/bin/env bash
# Pulls the current locklane.jar (latest build) into this directory and relaunches it
# (#289). Run from ~/.locklane, where install.sh put things — never touches
# application-locklane.properties.
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

echo "Downloading locklane.jar (latest build of $REPO)..."
gh release download latest --repo "$REPO" --pattern locklane.jar \
  --dir . --clobber

echo "Relaunching..."
exec java -jar locklane.jar
