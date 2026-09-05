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
  local p pidfile
  if [ -n "$T" ]; then
    # Every stub state dir under $T, not just the default one -- a scenario simulating
    # a second user (#691) spawns its "server" under its own stub2/pids.
    for pidfile in "$T"/*/pids; do
      [ -f "$pidfile" ] || continue
      rm -f "$(dirname "$pidfile")/kill-swallows"
      while read -r p; do
        [ -n "$p" ] || continue
        for c in $(pgrep -P "$p" 2>/dev/null); do /bin/kill -KILL "$c" 2>/dev/null; done
        /bin/kill -KILL "$p" 2>/dev/null
      done < "$pidfile"
    done
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
  pid="$(server_pid launchd.gui)"
  alive "$pid" || { bad "$name" "stub server not running after register"; return; }
  out="$("$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && contains "$out" "forced" && ! [ -f "$STUB_STATE/launchd.gui.loaded" ]; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) loaded=$([ -f "$STUB_STATE/launchd.gui.loaded" ] && echo yes || echo no) out=$out"
  fi
}

scenario_launchd_stop_graceful() {
  local name="launchd-stop-graceful" out rc pid
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  pid="$(server_pid launchd.gui)"
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
  pid="$(server_pid launchd.gui)"
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
  pid="$(server_pid launchd.gui)"
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
  pid="$(server_pid launchd.gui)"
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
  pid="$(server_pid launchd.gui)"
  out="$("$LL" uninstall --all --yes 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$pid" && [ ! -d "$HOME/.locklane" ] \
      && [ ! -f "$HOME/Library/LaunchAgents/com.locklane.server.plist" ] \
      && [ ! -f "$STUB_STATE/launchd.gui.loaded" ] && contains "$out" "no longer registered"; then
    ok "$name"
  else
    bad "$name" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) dir=$([ -d "$HOME/.locklane" ] && echo present || echo gone) out=$out"
  fi
}

scenario_uninstall_service_only_keeps_data() {
  local name="uninstall-service-only-keeps-data" out rc pid
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  pid="$(server_pid launchd.gui)"
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

scenario_uninstall_unregistered_but_loaded() {
  local name="uninstall-unregistered-but-loaded" out rc pid kind
  for kind in launchd systemd; do
    if [ "$kind" = systemd ] && in_server_cgroup; then
      skipped "$name ($kind)" "inside the locklane.service cgroup"
      continue
    fi

    # No service.env, no plist, no unit -- a botched manual cleanup's shape -- but the
    # label is still loaded/active. The verified stop still runs and --all still
    # deletes: detection must not skip straight to reporting stopped.
    new_home
    register_as "$kind" || { bad "$name ($kind, success)" "register"; continue; }
    pid="$(server_pid "$([ "$kind" = launchd ] && echo launchd.gui || echo systemd)")"
    rm -f "$HOME/.locklane/service.env"
    case "$kind" in
      launchd) rm -f "$HOME/Library/LaunchAgents/com.locklane.server.plist" ;;
      systemd) rm -f "$HOME/.config/systemd/user/locklane.service" ;;
    esac
    out="$("$LL" uninstall --all --yes 2>&1)"; rc=$?
    if [ "$rc" -eq 0 ] && ! alive "$pid" && [ ! -d "$HOME/.locklane" ]; then
      ok "$name ($kind, success)"
    else
      bad "$name ($kind, success)" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) dir=$([ -d "$HOME/.locklane" ] && echo present || echo gone) out=$out"
    fi

    # Same unregistered-but-loaded state, but the server (and the forced kill) never
    # actually go: this must fail loudly, never claim the old unverified "stopped".
    new_home
    [ "$kind" = systemd ] && touch "$STUB_STATE/systemd-no-escalate"
    register_as "$kind" ignore-term || { bad "$name ($kind, forced-failure)" "register"; continue; }
    pid="$(server_pid "$([ "$kind" = launchd ] && echo launchd.gui || echo systemd)")"
    rm -f "$HOME/.locklane/service.env"
    case "$kind" in
      launchd) rm -f "$HOME/Library/LaunchAgents/com.locklane.server.plist" ;;
      systemd) rm -f "$HOME/.config/systemd/user/locklane.service" ;;
    esac
    touch "$STUB_STATE/kill-swallows"
    out="$("$LL" uninstall --all --yes 2>&1)"; rc=$?
    rm -f "$STUB_STATE/kill-swallows"
    if [ "$rc" -ne 0 ] && alive "$pid" && [ -d "$HOME/.locklane" ] \
        && ! contains "$out" "is stopped and no longer registered"; then
      ok "$name ($kind, forced-failure)"
    else
      bad "$name ($kind, forced-failure)" "rc=$rc alive=$(alive "$pid" && echo yes || echo no) dir=$([ -d "$HOME/.locklane" ] && echo present || echo gone) out=$out"
    fi
  done
}

scenario_console_tab_refusal() {
  local name="console-tab-refusal" rc first sub
  for sub in stop uninstall; do
    new_home
    printf 'SERVICE_KIND=launchd\nREG_FILE=%s\n' "$HOME/Library/LaunchAgents/com.locklane.server.plist" > "$HOME/.locklane/service.env"
    # The "server" is a shell that runs the command itself, so the command's parent
    # chain reaches the pid the stub agent reports -- a console tab's exact shape.
    bash -c 'echo $$ > "$STUB_STATE/launchd.gui.pid"; touch "$STUB_STATE/launchd.gui.loaded"; "$1" "$2" > "$3/out" 2>&1; echo $? > "$3/rc"' _ "$LL" "$sub" "$T"
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

scenario_no_wrappers() {
  local name="no-wrappers" f
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  for f in status start stop uninstall update; do
    if [ -e "$HOME/.locklane/$f.sh" ]; then
      bad "$name" "$f.sh still exists after register"; return
    fi
  done
  if ! grep -q '^SERVICE_KIND=launchd$' "$HOME/.locklane/service.env"; then
    bad "$name" "service.env does not record the kind"; return
  fi
  ok "$name"
}

scenario_foreign_file_kept() {
  local name="foreign-file-kept" out
  new_home
  printf '#!/bin/sh\necho custom\n' > "$HOME/.locklane/stop.sh"
  chmod +x "$HOME/.locklane/stop.sh"
  out="$(LOCKLANE_SERVICE_KIND=launchd "$LL" register 2>&1)"
  if [ -f "$HOME/.locklane/stop.sh" ] && grep -q custom "$HOME/.locklane/stop.sh" && contains "$out" "stop.sh"; then
    ok "$name"
  else
    bad "$name" "kept=$([ -f "$HOME/.locklane/stop.sh" ] && echo yes || echo no) out=$out"
  fi
}

# An install already on the #678 wrapper layout: the five wrapper files register/update
# used to write (the four small ones carrying their real content, update.sh the actual
# released asset), a plist, a running agent, no service.env, no control program of its
# own yet. Its own update.sh (the released one) fetches the control program and hands
# over; `update` migrates the layout and, since #682, sheds the wrappers for good. The
# agent is planted in the "gui" domain, where such an install lived (ADR-110): update
# must retire that one (its pid gone) and bring a fresh one up in the same domain, not
# leave the old one running alongside. The user/<uid> retirement (a v0.2.11-v0.2.13
# install, #691) has its own scenario below.
old_layout() {
  local f
  echo "server.port=8080" > "$HOME/.locklane/application-locklane.properties"
  printf 'old jar' > "$HOME/.locklane/locklane.jar"
  for f in status start stop uninstall; do
    printf '#!/usr/bin/env bash\n# Transitional wrapper (#678): the control program is $HOME/.locklane/locklane -- run `locklane %s` directly. This file goes away in a later release.\nexec "$(dirname "$0")/locklane" %s "$@"\n' \
        "$f" "$f" > "$HOME/.locklane/$f.sh"
    chmod +x "$HOME/.locklane/$f.sh"
  done
  mkdir -p "$HOME/Library/LaunchAgents"
  printf '<plist/>\n' > "$HOME/Library/LaunchAgents/com.locklane.server.plist"
  touch "$STUB_STATE/launchd.gui.loaded"
  nohup bash -c 'while :; do sleep 1; done' >/dev/null 2>&1 </dev/null &
  echo $! > "$STUB_STATE/launchd.gui.pid"
  echo $! >> "$STUB_STATE/pids"
  disown
}

scenario_migrate_old_layout() {
  local name="migrate-old-layout" out rc old_pid f
  new_home
  rm -f "$LL"
  cp "$ROOT/update.sh" "$HOME/.locklane/update.sh"; chmod +x "$HOME/.locklane/update.sh"
  old_layout
  old_pid="$(server_pid launchd.gui)"
  # Exactly how a v0.2.8+ install's update.sh arrives here: cwd is the install
  # directory and the re-exec guard is set.
  out="$(cd "$HOME/.locklane" && LOCKLANE_UPDATE_REEXEC=1 ./update.sh 2>&1)"; rc=$?
  for f in status start stop uninstall update; do
    if [ -e "$HOME/.locklane/$f.sh" ]; then
      bad "$name" "$f.sh still exists after migration"
      return
    fi
  done
  if [ "$rc" -eq 0 ] && [ -x "$LL" ] && ! alive "$old_pid" && alive "$(server_pid launchd.gui)" \
      && [ -f "$STUB_STATE/launchd.gui.loaded" ] && [ ! -f "$STUB_STATE/launchd.user.loaded" ] \
      && grep -q '^SERVICE_KIND=launchd$' "$HOME/.locklane/service.env" \
      && [ "$(head -c 2 "$HOME/.locklane/locklane.jar")" = PK ] \
      && contains "$out" "Installed locklane v9.9.9-stub" && grep -q 'Environment\|PATH' "$HOME/Library/LaunchAgents/com.locklane.server.plist" \
      && contains "$("$LL" status 2>&1)" "is running as the launchd agent" \
      && ! contains "$("$LL" status 2>&1)" "moves it back"; then
    ok "$name"
  else
    bad "$name" "rc=$rc control=$([ -x "$LL" ] && echo yes || echo no) old-alive=$(alive "$old_pid" && echo yes || echo no) gui-loaded=$([ -f "$STUB_STATE/launchd.gui.loaded" ] && echo yes || echo no) out=$out"
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
  [ "$rc" -eq 0 ] && alive "$(server_pid launchd.gui)" || { bad "$name" "start: rc=$rc out=$out"; return; }
  out="$("$LL" start 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && contains "$out" "already running" || { bad "$name" "second start: rc=$rc out=$out"; return; }
  out="$("$LL" restart 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && alive "$(server_pid launchd.gui)" || { bad "$name" "restart: rc=$rc out=$out"; return; }
  ok "$name"
}

# ADR-110: the registration domain is gui/<uid>, the one an ordinary LaunchAgent plist
# can load into on a real Mac. user/<uid> (#691) refused it there (launchd 134).
scenario_domain_is_gui_not_user() {
  local name="domain-is-gui-not-user" out
  new_home
  register_as launchd || { bad "$name" "register"; return; }
  out="$(cat "$STUB_STATE/launchctl.log" 2>/dev/null)"
  if grep -q "^bootstrap gui/$(id -u) " <<<"$out" && ! grep -q "^bootstrap user/" <<<"$out"; then
    ok "$name"
  else
    bad "$name" "register did not bootstrap into gui/\$(id -u): log=$out"
  fi
}

# An install v0.2.11-v0.2.13 managed to load into user/<uid> (#691) is found there,
# retired, and re-registered in gui/<uid> by one `register` (ADR-110): status names the
# leftover domain beforehand and not afterwards, the old pid is gone, both domains are
# swept and only gui is loaded at the end.
scenario_retires_user_domain_agent() {
  local name="retires-user-domain-agent" out rc old_pid
  new_home
  mkdir -p "$HOME/Library/LaunchAgents"
  printf '<plist/>\n' > "$HOME/Library/LaunchAgents/com.locklane.server.plist"
  printf 'SERVICE_KIND=launchd\nREG_FILE=%s\n' "$HOME/Library/LaunchAgents/com.locklane.server.plist" > "$HOME/.locklane/service.env"
  touch "$STUB_STATE/launchd.user.loaded"
  nohup bash -c 'while :; do sleep 1; done' >/dev/null 2>&1 </dev/null &
  echo $! > "$STUB_STATE/launchd.user.pid"
  echo $! >> "$STUB_STATE/pids"
  disown
  old_pid="$(server_pid launchd.user)"
  out="$("$LL" status 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && contains "$out" "moves it back to gui" || { bad "$name" "status before: rc=$rc out=$out"; return; }
  register_as launchd || { bad "$name" "register"; return; }
  out="$("$LL" status 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && ! alive "$old_pid" && alive "$(server_pid launchd.gui)" \
      && [ -f "$STUB_STATE/launchd.gui.loaded" ] && [ ! -f "$STUB_STATE/launchd.user.loaded" ] \
      && grep -q "^bootout user/$(id -u)/" "$STUB_STATE/launchctl.log" \
      && contains "$out" "is running as the launchd agent" && ! contains "$out" "moves it back"; then
    ok "$name"
  else
    bad "$name" "rc=$rc old-alive=$(alive "$old_pid" && echo yes || echo no) gui-loaded=$([ -f "$STUB_STATE/launchd.gui.loaded" ] && echo yes || echo no) user-loaded=$([ -f "$STUB_STATE/launchd.user.loaded" ] && echo yes || echo no) out=$out"
  fi
}

# A refused bootstrap leaves the plist in place and says where launchd's real reason
# is (#703): the pre-#703 program deleted the plist, so one refused load left an
# install that could not start, restart or register. A later register, once the cause
# is gone, must succeed from that state; `start` reports a refusal the same way.
scenario_bootstrap_failure_keeps_plist() {
  local name="bootstrap-failure-keeps-plist" out rc plist
  new_home
  plist="$HOME/Library/LaunchAgents/com.locklane.server.plist"
  touch "$STUB_STATE/bootstrap-fails"
  out="$(LOCKLANE_SERVICE_KIND=launchd "$LL" register 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] || [ ! -f "$plist" ] || ! contains "$out" "Input/output error" \
      || ! contains "$out" "log show" || ! contains "$out" "left at $plist"; then
    bad "$name" "register: rc=$rc plist=$([ -f "$plist" ] && echo kept || echo GONE) out=$out"; return
  fi
  rm -f "$STUB_STATE/bootstrap-fails"
  register_as launchd || { bad "$name" "register after the cause is gone"; return; }
  alive "$(server_pid launchd.gui)" || { bad "$name" "not running after the second register"; return; }
  "$LL" stop >/dev/null 2>&1
  touch "$STUB_STATE/bootstrap-fails"
  out="$("$LL" start 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ] && [ -f "$plist" ] && contains "$out" "Input/output error" && contains "$out" "log show"; then
    ok "$name"
  else
    bad "$name" "start: rc=$rc plist=$([ -f "$plist" ] && echo kept || echo GONE) out=$out"
  fi
}

# Two different users on the same Mac (#691's other Done-when bullet): each gets their
# own HOME, their own stub state (a real second account gets this for free), and --
# simulated here via the stub id -- their own uid, so the launchd domain each one
# registers into is genuinely per-user (gui/501 vs gui/502), not a hardcoded value.
# Neither install collides with, nor can stop, the other's.
scenario_two_users_no_collision() {
  local name="two-users-no-collision" out rc pid1 pid2 home2 stub2 ll2
  new_home
  home2="$T/home2"; stub2="$T/stub2"
  mkdir -p "$home2/.locklane" "$stub2"
  cp "$ROOT/scripts/locklane" "$home2/.locklane/locklane"; chmod +x "$home2/.locklane/locklane"
  printf 'PK stub jar' > "$home2/.locklane/locklane.jar"
  ll2="$home2/.locklane/locklane"

  STUB_UID=501 register_as launchd || { bad "$name" "user A register"; return; }
  pid1="$(server_pid launchd.gui)"
  alive "$pid1" || { bad "$name" "user A not running after register"; return; }

  out="$(HOME="$home2" STUB_STATE="$stub2" STUB_UID=502 LOCKLANE_SERVICE_KIND=launchd "$ll2" register 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then bad "$name" "user B register: rc=$rc out=$out"; return; fi
  pid2="$(cat "$stub2/launchd.gui.pid" 2>/dev/null)"
  alive "$pid2" || { bad "$name" "user B not running after register"; return; }
  [ "$pid1" != "$pid2" ] || { bad "$name" "user A and user B share a pid -- not isolated"; return; }
  if ! grep -q "^bootstrap gui/501 " "$STUB_STATE/launchctl.log" 2>/dev/null \
      || ! grep -q "^bootstrap gui/502 " "$stub2/launchctl.log" 2>/dev/null; then
    bad "$name" "domain string did not carry each user's own uid"
    return
  fi

  out="$(STUB_UID=501 "$LL" stop 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ] || alive "$pid1"; then bad "$name" "stopping user A: rc=$rc out=$out"; return; fi
  if ! alive "$pid2"; then
    bad "$name" "stopping user A's agent also took down user B's (pid $pid2)"
    return
  fi

  out="$(HOME="$home2" STUB_STATE="$stub2" STUB_UID=502 "$ll2" stop 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ] || alive "$pid2"; then bad "$name" "stopping user B: rc=$rc out=$out"; return; fi
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
scenario_uninstall_unregistered_but_loaded
scenario_console_tab_refusal
scenario_no_wrappers
scenario_foreign_file_kept
scenario_migrate_old_layout
scenario_install_hands_over_to_update
scenario_detached_refused
scenario_status_and_start
scenario_domain_is_gui_not_user
scenario_retires_user_domain_agent
scenario_bootstrap_failure_keeps_plist
scenario_two_users_no_collision

echo
echo "lifecycle harness: $pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ] || { echo "failed:$failures"; exit 1; }
exit 0
