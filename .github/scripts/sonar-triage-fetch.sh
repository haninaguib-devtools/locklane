#!/usr/bin/env bash
# Fetch open SonarQube issues for the nightly triage report (#909).
#
# Usage:
#   SONAR_HOST_URL=<host> SONAR_TOKEN=<token> [.github/scripts/sonar-triage-fetch.sh [output.json]]
#
# Reads SONAR_HOST_URL and SONAR_TOKEN from the environment (the `Sonar triage`
# workflow passes `vars.SONAR_HOST` and `secrets.SONAR_TOKEN`), queries
# `api/issues/search` for project `locklane` (overridable via SONAR_PROJECT_KEY),
# pages past the 100-issue default page size, drops anything tagged `tracked`,
# and writes `{sonarHost, projectKey, issues}` JSON to stdout (or to the optional
# output path). Requires `curl` and `jq`.
set -euo pipefail

SONAR_HOST_URL="${SONAR_HOST_URL:-}"
SONAR_TOKEN="${SONAR_TOKEN:-}"
PROJECT_KEY="${SONAR_PROJECT_KEY:-locklane}"
PAGE_SIZE="${SONAR_PAGE_SIZE:-500}"

if [ -z "$SONAR_HOST_URL" ] || [ -z "$SONAR_TOKEN" ]; then
  echo "sonar-triage-fetch: SONAR_HOST_URL and SONAR_TOKEN must both be set" >&2
  exit 1
fi

# Normalise for output links and API calls alike: no trailing slash.
SONAR_HOST_URL="${SONAR_HOST_URL%/}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

page=1
total=1
: > "$tmpdir/issues.jsonl"

while :; do
  resp="$tmpdir/page-$page.json"
  curl -sSf -u "$SONAR_TOKEN:" \
    "$SONAR_HOST_URL/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=$PAGE_SIZE&p=$page" \
    -o "$resp"

  if [ "$page" -eq 1 ]; then
    total="$(jq -r '.total // 0' "$resp")"
  fi
  jq -c '.issues[]?' "$resp" >> "$tmpdir/issues.jsonl"

  fetched="$(wc -l < "$tmpdir/issues.jsonl" | tr -d ' ')"
  if [ "$fetched" -ge "$total" ]; then
    break
  fi
  page=$((page + 1))
  if [ "$page" -gt 100 ]; then
    echo "sonar-triage-fetch: refusing to fetch more than 100 pages" >&2
    exit 1
  fi
done

out="$(jq -n \
  --arg sonarHost "$SONAR_HOST_URL" \
  --arg projectKey "$PROJECT_KEY" \
  --slurpfile issues <(jq -s 'map(select((.tags // []) | index("tracked") | not))' "$tmpdir/issues.jsonl") \
  '{sonarHost: $sonarHost, projectKey: $projectKey, issues: $issues[0]}')"

if [ $# -gt 0 ]; then
  printf '%s\n' "$out" > "$1"
else
  printf '%s\n' "$out"
fi
