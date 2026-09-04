#!/usr/bin/env bash
# The lifecycle harness (#678): drives scripts/locklane through every stop, uninstall,
# refusal and migration scenario against stub service managers (stubs/), in a scratch
# HOME, with the grace periods shortened. Exit 0 when every scenario passes; each
# scenario prints PASS or FAIL with what it saw. Run from anywhere:
#   bash scripts/tests/lifecycle/run.sh
# Needs bash, coreutils, pgrep and /bin/kill -- nothing else. Never touches a real
# service manager: PATH is fronted with the stubs before any scenario runs.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
STUBS="$ROOT/scripts/tests/lifecycle/stubs"
export LIFECYCLE_ROOT="$ROOT"
export LOCKLANE_STOP_GRACE=2
export LOCKLANE_FORCE_WAIT=2
unset LOCKLANE_SERVICE_KIND LOCKLANE_UPDATE_REEXEC

# From inside a Locklane console on Linux this harness itself sits in the server's
# cgroup, and the control program's own refusal (correctly) fires for every systemd
# stop. Move out into a transient scope first, when systemd can; otherwise the systemd
# scenarios are skipped, saying so.
in_server_cgroup() { grep -qs 'locklane\.service' /proc/self/cgroup; }
if in_server_cgroup && [ -z "${LIFECYCLE_REEXEC:-}" ] && command -v systemd-run >/dev/null 2>&1; then
  if LIFECYCLE_REEXEC=1 exec systemd-run --user --scope --quiet --collect \
      env LIFECYCLE_REEXEC=1 bash "$0" "$@"; then :; fi
  echo "note: could not leave the locklane.service cgroup; systemd scenarios will be skipped." >&2
fi

export PATH="$STUBS:$PATH"

pass=0
fail=0
skip=0
failures=""
T=""
LL=""

ok()   { pass=$((pass + 1)); echo "PASS  $1"; }
bad()  { fail=$((fail + 1)); failures="$failures $1"; echo "FAIL  $1 -- $2"; }
skipped() { skip=$((skip + 1)); echo "SKIP  $1 -- $2"; }

# A fresh scratch HOME holding a copy of the control program, and empty stub state.
new_home() {
  cleanup
  T="$(mktemp -d)"
  export HOME="$T/home"
  export STUB_STATE="$T/stub"
  mkdir -p "$HOME/.locklane" "$STUB_STATE"
  cp "$ROOT/scripts/locklane" "$HOME/.locklane/locklane"
  chmod +x "$HOME/.locklane/locklane"
  # register refuses without a jar to run; the stubs never execute it.
  printf 'PK stub jar' > "$HOME/.locklane/locklane.jar"
  LL="$HOME/.locklane/locklane"
}
cleanup() {
  local p
  if [ -n "$T" ] && [ -f "$T/stub/pids" ]; then
    rm -f "$T/stub/kill-swallows"
    while read -r p; do
      [ -n "$p" ] || continue
      for c in $(pgrep -P "$p" 2>/dev/null); do /bin/kill -KILL "$c" 2>/dev/null; done
      /bin/kill -KILL "$p" 2>/dev/null
    done < "$T/stub/pids"
  fi
  [ -n "$T" ] && rm -rf "$T"
  T=""
}
trap cleanup EXIT

alive() { [ -n "${1:-}" ] && /bin/kill -0 "$1" 2>/dev/null; }
server_pid() { cat "$STUB_STATE/$1.pid" 2>/dev/null; }
contains() { printf '%s' "$1" | grep -q -- "$2"; }

# Registers under the given kind with the stub server in the given mode.
register_as() {
  local kind="$1" mode="${2:-}"
  [ -n "$mode" ] && echo "$mode" > "$STUB_STATE/server-mode"
  LOCKLANE_SERVICE_KIND="$kind" "$LL" register > "$T/register.out" 2>&1 || {
    echo "  register failed:"; sed 's/^/    /' "$T/register.out"; return 1; }
}

# --- Scenarios --------------------------------------------------------------------------

scenario_launchd_stop_forced() {
  local name="launchd-stop-forced" out rc pid
  new_home
  register_as launchd ignore-term || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  alive "$pid" || { bad "$name" "stub server not running after register"; return; }
  out="$("$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && contains "$out" "forced" && ! [ -f "$STUB_STATE/launchd.loaded" ]; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) loaded=$([ -f "$STUB_STATE/launchd.loaded" ] && echo yes || echo no) out=$out"
  fi
}

scenario_launchd_stop_graceful() {
  local name="launchd-stop-graceful" out rc pid
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  out="$("$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && contains "$out" "^Stopped\.$" && ! contains "$out" "forced"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) out=$out"
  fi
}

scenario_launchd_stop_kills_children() {
  local name="launchd-stop-kills-children" out rc pid child
  new_home
  register_as launchd with-child || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  child=""
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    child="$(pgrep -P "$pid" 2>/dev/null | head -n 1)"
    [ -n "$child" ] && break
    sleep 0.2
  done
  [ -n "$child" ] || { bad "$name" "the stub server never spawned its child"; return; }
  out="$("$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && ! alive "$child"; then
    ok "$name"
  else
    bad "$name" "rc=$rc server-alive=$(alive "$pid" && echo yes || echo no) child-alive=$(alive "$child" && echo yes || echo no) out=$out"
  fi
}

scenario_systemd_stop_forced() {
  local name="systemd-stop-forced" out rc pid
  if in_server_cgroup; then skipped "$name" "inside the locklane.service cgroup"; return; fi
  new_home
  touch "$STUB_STATE/systemd-no-escalate"
  register_as systemd ignore-term || { bad "$name" "register"; return; }
  pid="$(server_pid systemd)"
  out="$("$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && contains "$out" "forced"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) out=$out"
  fi
}

scenario_systemd_stop_graceful() {
  local name="systemd-stop-graceful" out rc pid
  if in_server_cgroup; then skipped "$name" "inside the locklane.service cgroup"; return; fi
  new_home
  register_as systemd || { bad "$name" "register"; return; }
  pid="$(server_pid systemd)"
  out="$("$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && contains "$out" "^Stopped\.$"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) out=$out"
  fi
}

scenario_stop_cannot_finish() {
  local name="stop-cannot-finish" out rc pid
  new_home
  register_as launchd ignore-term || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  touch "$STUB_STATE/kill-swallows"
  out="$("$LL" stop 2>&1)"; rc=$?
  rm -f "$STUB_STATE/kill-swallows"
  if [ "$rc" -eq 1 ] && alive "$pid" && contains "$out" "error: not stopped" \
      && contains "$out" "server pid $pid: still alive" && contains "$out" "agent com.locklane.server: still loaded" \
      && ! contains "$out" "^Stopped"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) out=$out"
  fi
}

scenario_uninstall_refuses_live() {
  local name="uninstall-refuses-live" out rc pid
  new_home
  register_as launchd ignore-term || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  cp -a "$HOME/.locklane" "$T/before"
  touch "$STUB_STATE/kill-swallows"
  out="$("$LL" uninstall --all --yes 2>&1)"; rc=$?
  rm -f "$STUB_STATE/kill-swallows"
  if [ "$rc" -ne 0 ] && alive "$pid" && diff -r "$HOME/.locklane" "$T/before" >/dev/null \
      && [ -f "$HOME/Library/LaunchAgents/com.locklane.server.plist" ] \
      && contains "$out" "still running" && contains "$out" "nothing was removed"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) dir-diff=$(diff -r "$HOME/.locklane" "$T/before" >/dev/null && echo same || echo changed) out=$out"
  fi
}

scenario_uninstall_clean() {
  local name="uninstall-clean" out rc pid
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  out="$("$LL" uninstall --all --yes 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && [ ! -d "$HOME/.locklane" ] \
      && [ ! -f "$HOME/Library/LaunchAgents/com.locklane.server.plist" ] \
      && [ ! -f "$STUB_STATE/launchd.loaded" ] && contains "$out" "no longer registered"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) dir=$([ -d "$HOME/.locklane" ] && echo present || echo gone) out=$out"
  fi
}

scenario_uninstall_service_only_keeps_data() {
  local name="uninstall-service-only-keeps-data" out rc pid
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  pid="$(server_pid launchd)"
  echo "server.port=8080" > "$HOME/.locklane/application-locklane.properties"
  out="$("$LL" uninstall --service-only 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && [ -f "$HOME/.locklane/application-locklane.properties" ] \
      && [ ! -f "$HOME/.locklane/service.env" ] && [ ! -f "$HOME/Library/LaunchAgents/com.locklane.server.plist" ] \
      && contains "$out" "was kept"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) out=$out"
  fi
}

scenario_console_tab_refusal() {
  local name="console-tab-refusal" rc first sub
  for sub in stop uninstall; do
    new_home
    printf 'SERVICE_KIND=launchd\nREG_FILE=%s\n' "$HOME/Library/LaunchAgents/com.locklane.server.plist" > "$HOME/.locklane/service.env"
    # The "server" is a shell that runs the command itself, so the command's parent
    # chain reaches the pid the stub agent reports -- a console tab's exact shape.
    bash -c 'echo $$ > "$STUB_STATE/launchd.pid"; touch "$STUB_STATE/launchd.loaded"; "$1" "$2" > "$3/out" 2>&1; echo $? > "$3/rc"' _ "$LL" "$sub" "$T"
    rc="$(cat "$T/rc" 2>/dev/null)"
    first="$(head -n 1 "$T/out" 2>/dev/null)"
    if [ "$rc" = 2 ] && [ "${first#error: not run}" != "$first" ] \
        && grep -q "console tab" "$T/out" && grep -q "IDE terminal" "$T/out" \
        && [ -f "$HOME/.locklane/service.env" ]; then
      ok "$name ($sub)"
    else
      bad "$name ($sub)" "rc=$rc first=$first"
    fi
  done
}

scenario_wrappers() {
  local name="wrappers" f lines
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  for f in status start stop uninstall; do
    lines="$(wc -l < "$HOME/.locklane/$f.sh" | tr -d ' ')"
    if [ "$lines" -gt 4 ] || ! grep -q "^exec \"\$(dirname \"\$0\")/locklane\" $f \"\$@\"$" "$HOME/.locklane/$f.sh" \
        || [ ! -x "$HOME/.locklane/$f.sh" ]; then
      bad "$name" "$f.sh is not a three-line wrapper"; return
    fi
  done
  if ! cmp -s "$HOME/.locklane/update.sh" "$ROOT/update.sh"; then
    bad "$name" "the written update.sh differs from the repository's update.sh (the release asset)"; return
  fi
  if ! grep -q '^SERVICE_KIND=launchd$' "$HOME/.locklane/service.env"; then
    bad "$name" "service.env does not record the kind"; return
  fi
  ok "$name"
}

# An install from before this program: generated scripts, a plist, a running agent,
# no service.env, no control program. Its own update.sh (the released one) fetches the
# control program and hands over; `update` migrates the layout.
old_layout() {
  local f
  echo "server.port=8080" > "$HOME/.locklane/application-locklane.properties"
  printf 'old jar' > "$HOME/.locklane/locklane.jar"
  for f in status start stop uninstall; do
    printf '#!/usr/bin/env bash\n# generated by the old install.sh\necho old\n' > "$HOME/.locklane/$f.sh"
  done
  mkdir -p "$HOME/Library/LaunchAgents"
  printf '<plist/>\n' > "$HOME/Library/LaunchAgents/com.locklane.server.plist"
  touch "$STUB_STATE/launchd.loaded"
  nohup bash -c 'while :; do sleep 1; done' >/dev/null 2>&1 </dev/null &
  echo $! > "$STUB_STATE/launchd.pid"
  echo $! >> "$STUB_STATE/pids"
  disown
}

scenario_migrate_old_layout() {
  local name="migrate-old-layout" out rc old_pid
  new_home
  rm -f "$LL"
  cp "$ROOT/update.sh" "$HOME/.locklane/update.sh"; chmod +x "$HOME/.locklane/update.sh"
  old_layout
  old_pid="$(server_pid launchd)"
  # Exactly how a v0.2.8+ install's update.sh arrives here: cwd is the install
  # directory and the re-exec guard is set.
  out="$(cd "$HOME/.locklane" && LOCKLANE_UPDATE_REEXEC=1 ./update.sh 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && [ -x "$LL" ] && ! alive "$old_pid" && alive "$(server_pid launchd)" \
      && grep -q '^SERVICE_KIND=launchd$' "$HOME/.locklane/service.env" \
      && grep -q 'locklane" stop' "$HOME/.locklane/stop.sh" && grep -q 'locklane" uninstall' "$HOME/.locklane/uninstall.sh" \
      && cmp -s "$HOME/.locklane/update.sh" "$ROOT/update.sh" \
      && [ "$(head -c 2 "$HOME/.locklane/locklane.jar")" = PK ] \
      && contains "$out" "Installed locklane v9.9.9-stub" && grep -q 'Environment\|PATH' "$HOME/Library/LaunchAgents/com.locklane.server.plist"; then
    ok "$name"
  else
    bad "$name" "rc=$rc control=$([ -x "$LL" ] && echo yes || echo no) old-alive=$(alive "$old_pid" && echo yes || echo no) out=$out"
  fi
}

scenario_install_hands_over_to_update() {
  local name="install-hands-over-to-update" out rc
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  echo "server.port=8080" > "$HOME/.locklane/application-locklane.properties"
  cp "$HOME/.locklane/application-locklane.properties" "$T/props.before"
  printf 'old jar' > "$HOME/.locklane/locklane.jar"
  out="$("$LL" install 2>&1 < /dev/null)"; rc=$?
  if [ "$rc" -eq 0 ] && contains "$out" "already installed" && contains "$out" "Installed locklane v9.9.9-stub" \
      && cmp -s "$HOME/.locklane/application-locklane.properties" "$T/props.before" \
      && [ ! -f "$HOME/.locklane/install-seed.log" ]; then
    ok "$name"
  else
    bad "$name" "rc=$rc out=$out"
  fi
}

scenario_detached_refused() {
  local name="detached-refused" out rc pid sub
  for sub in update stop uninstall; do
    new_home
    nohup bash -c 'while :; do sleep 1; done' >/dev/null 2>&1 </dev/null &
    pid=$!; echo "$pid" >> "$STUB_STATE/pids"; disown
    echo "$pid" > "$HOME/.locklane/locklane.pid"
    out="$("$LL" "$sub" 2>&1)"; rc=$?
    if [ "$rc" -ne 0 ] && alive "$pid" && contains "$out" "detached" && contains "$out" "Nothing was changed" \
        && [ -f "$HOME/.locklane/locklane.pid" ]; then
      ok "$name ($sub)"
    else
      bad "$name ($sub)" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) out=$out"
    fi
  done
}

scenario_status_and_start() {
  local name="status-and-start" out rc
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  out="$("$LL" status 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && contains "$out" "is running" || { bad "$name" "status while running: rc=$rc out=$out"; return; }
  "$LL" stop >/dev/null 2>&1
  out="$("$LL" status 2>&1)"; rc=$?
  [ "$rc" -ne 0 ] && contains "$out" "not running" || { bad "$name" "status while stopped: rc=$rc out=$out"; return; }
  out="$("$LL" start 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && alive "$(server_pid launchd)" || { bad "$name" "start: rc=$rc out=$out"; return; }
  out="$("$LL" start 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && contains "$out" "already running" || { bad "$name" "second start: rc=$rc out=$out"; return; }
  out="$("$LL" restart 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && alive "$(server_pid launchd)" || { bad "$name" "restart: rc=$rc out=$out"; return; }
  ok "$name"
}

scenario_parses_and_help() {
  local name="parses-and-help" out rc
  if ! bash -n "$ROOT/scripts/locklane" || ! bash -n "$ROOT/install.sh" || ! bash -n "$ROOT/update.sh"; then
    bad "$name" "bash -n failed"; return
  fi
  new_home
  out="$("$LL" help 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && contains "$out" "uninstall" || { bad "$name" "help: rc=$rc"; return; }
  out="$("$LL" bogus 2>&1)"; rc=$?
  [ "$rc" -eq 2 ] || { bad "$name" "unknown command should exit 2, got $rc"; return; }
  ok "$name"
}

scenario_parses_and_help
scenario_launchd_stop_graceful
scenario_launchd_stop_forced
scenario_launchd_stop_kills_children
scenario_systemd_stop_graceful
scenario_systemd_stop_forced
scenario_stop_cannot_finish
scenario_uninstall_refuses_live
scenario_uninstall_clean
scenario_uninstall_service_only_keeps_data
scenario_console_tab_refusal
scenario_wrappers
scenario_migrate_old_layout
scenario_install_hands_over_to_update
scenario_detached_refused
scenario_status_and_start

echo
echo "lifecycle harness: $pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ] || { echo "failed:$failures"; exit 1; }
exit 0
