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

# --- Detect how the server was started (#386) -----------------------------------
# install.sh sets up a systemd user service or a launchd agent when the platform
# supports one, falling back to #385's plain detached start otherwise. Whichever it
# is, this script must stop and restart it the same way — never leave the service
# running while also launching a second, competing copy by hand.

unit_file="$HOME/.config/systemd/user/locklane.service"
agent_label="com.locklane.server"
agent_file="$HOME/Library/LaunchAgents/$agent_label.plist"
if [ -f "$unit_file" ] && command -v systemctl >/dev/null 2>&1; then
  service_kind="systemd"
elif [ -f "$agent_file" ] && command -v launchctl >/dev/null 2>&1; then
  service_kind="launchd"
else
  service_kind=""
fi

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

# --- Stop the running instance, if any -----------------------------------------
# Must happen before the jar is replaced (#385): overwriting locklane.jar with
# --clobber while the running JVM still holds that file open, then starting a second
# server on the port the first one still has bound, leaves the old server damaged and
# the new one not running. A missing pid file, or a pid that is no longer running, is
# not an error -- there is simply nothing to stop.

pid_file="locklane.pid"
case "$service_kind" in
  systemd)
    echo "Stopping the locklane service..."
    systemctl --user stop locklane
    ;;
  launchd)
    echo "Stopping the locklane agent..."
    # bootout also fully unloads it, which is what lets the KeepAlive agent stay down
    # for the download below instead of relaunching itself immediately.
    launchctl bootout "gui/$(id -u)/$agent_label" 2>/dev/null || true
    ;;
  *)
    if [ -f "$pid_file" ]; then
      old_pid="$(cat "$pid_file")"
      if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        echo "Stopping the running server (pid $old_pid)..."
        kill "$old_pid"
        for _ in $(seq 1 30); do
          kill -0 "$old_pid" 2>/dev/null || break
          sleep 1
        done
        if kill -0 "$old_pid" 2>/dev/null; then
          echo "error: server (pid $old_pid) did not stop within 30s." >&2
          exit 1
        fi
      fi
      rm -f "$pid_file"
    fi
    ;;
esac

echo "Downloading locklane.jar (latest build of $REPO)..."
gh release download latest --repo "$REPO" --pattern locklane.jar \
  --dir . --clobber

# --- Start the new build ---------------------------------------------------------
# A service-managed install (#386) is restarted the same way it was started; the
# fallback (#385) relaunches detached the same way install.sh does: nohup ignores the
# SIGHUP the shell sends its children on exit, and disown drops it from the shell's own
# job table, the second, independent way a closing terminal can take a background job
# down with it.

case "$service_kind" in
  systemd)
    echo "Restarting the locklane service..."
    systemctl --user start locklane
    ;;
  launchd)
    echo "Restarting the locklane agent..."
    launchctl bootstrap "gui/$(id -u)" "$agent_file"
    ;;
  *)
    log_file="locklane.log"
    echo "Relaunching..."
    nohup java -jar locklane.jar > "$log_file" 2>&1 < /dev/null &
    echo $! > "$pid_file"
    disown
    ;;
esac
