#!/usr/bin/env bash
# Updates an installed locklane (#678). This file is also the release asset an older
# install's update.sh fetches and execs before updating (#647), so it stays
# self-sufficient: it fetches the control program when this directory has none yet,
# then hands over to `locklane update`. Otherwise it is a transitional wrapper -- run
# `locklane update` directly; this file goes away in a later release.
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd -P)"
if [ ! -x "$dir/locklane" ]; then
  echo "Fetching the locklane control program..."
  if ! gh release download --repo haninaguib-devtools/locklane --pattern locklane \
      --output "$dir/locklane" --clobber 2>/dev/null; then
    curl -fsSL https://raw.githubusercontent.com/haninaguib-devtools/locklane/main/scripts/locklane \
      > "$dir/locklane"
  fi
  chmod +x "$dir/locklane"
fi
exec "$dir/locklane" update "$@"
