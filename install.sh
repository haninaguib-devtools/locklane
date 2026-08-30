#!/usr/bin/env bash
# Installs locklane into ~/.locklane (#289): downloads the current engine jar, asks the
# handful of settings a fresh install needs, and writes them to
# ~/.locklane/application-locklane.properties. Fetches update.sh alongside it for
# pulling a newer jar later without repeating any of this.
#
# The login account is created here and now, by running the jar once in its seed-only
# mode with the credentials in the environment (#384). The password is therefore never
# written to disk and never appears in `ps` output -- the properties file ends up
# holding only the port and the allowed origins.
#
# Usage: curl -fsSL <url-to-this-file> | bash
set -euo pipefail

# Mirrors engine/src/main/resources/application.yml's
# locklane.release-check.repository — the only release channel that exists today.
REPO="haninaguib-devtools/locklane"
INSTALL_DIR="${LOCKLANE_HOME:-$HOME/.locklane}"

if ! command -v gh >/dev/null 2>&1; then
  echo "error: the GitHub CLI (gh) is required — install it from https://cli.github.com, then run 'gh auth login'." >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "error: gh is not logged in — run 'gh auth login' first." >&2
  exit 1
fi
# Needed by the installer itself, not just later: the seeding run below is this jar.
if ! command -v java >/dev/null 2>&1; then
  echo "error: java 21 or newer is required — install a JDK, then re-run this installer." >&2
  exit 1
fi
java_bin="$(command -v java)"

mkdir -p "$INSTALL_DIR"

echo "Downloading locklane.jar (latest build of $REPO)..."
gh release download latest --repo "$REPO" --pattern locklane.jar \
  --dir "$INSTALL_DIR" --clobber

echo "Fetching update.sh..."
gh api -H "Accept: application/vnd.github.raw" "repos/$REPO/contents/update.sh" \
  > "$INSTALL_DIR/update.sh"
chmod +x "$INSTALL_DIR/update.sh"

# --- Prompts ------------------------------------------------------------------

port=""
while true; do
  read -r -p "Port to run on [8080]: " port < /dev/tty
  port="${port:-8080}"
  case "$port" in
    ''|*[!0-9]*) echo "Enter a port number." >&2; continue ;;
  esac
  [ "$port" -ge 1 ] && [ "$port" -le 65535 ] && break
  echo "Port must be between 1 and 65535." >&2
done

read -r -p "Extra allowed origins, comma-separated (localhost:$port is always allowed) []: " extra_origins < /dev/tty
origins="http://localhost:$port"
if [ -n "$extra_origins" ]; then
  origins="$origins,$extra_origins"
fi

read -r -p "Bootstrap username [admin]: " username < /dev/tty
username="${username:-admin}"

password=""
while true; do
  read -r -s -p "Bootstrap password: " password < /dev/tty
  echo
  if [ -z "$password" ]; then
    echo "Password cannot be empty." >&2
    continue
  fi
  read -r -s -p "Confirm password: " password_confirm < /dev/tty
  echo
  if [ "$password" != "$password_confirm" ]; then
    echo "Passwords did not match — try again." >&2
    continue
  fi
  break
done

# --- Write the properties file ------------------------------------------------
# The credentials deliberately do not go in here (#384) — they are used once, by the
# seeding run below, and then forgotten. Mode 600 anyway: nothing is gained by making
# a config file wider than the account that owns it.

props_file="$INSTALL_DIR/application-locklane.properties"
: > "$props_file"
chmod 600 "$props_file"
{
  echo "server.port=$port"
  echo "locklane.security.allowed-origins=$origins"
} > "$props_file"

# --- Create the login account -------------------------------------------------
# One run of the jar that seeds the account and stops. The credentials go through the
# environment, never the command line, so they stay out of `ps`. Run from
# $INSTALL_DIR, exactly as the server itself will be, so both use the same database.
# Port 0 keeps this run off whatever port an already-running instance may hold.
# Exit 3 is UserBootstrapper.EXIT_ALREADY_SEEDED: the database already had an account,
# so what was typed above was not applied and saying otherwise would be a lie.

echo "Creating the login account..."
seed_log="$INSTALL_DIR/install-seed.log"
: > "$seed_log"
chmod 600 "$seed_log"
# stdin comes from /dev/null, not the terminal and not the pipe this script may itself
# be being read from under `curl | bash` (#354's lesson) -- java has no business
# reading either.
seed_status=0
(
  cd "$INSTALL_DIR"
  LOCKLANE_SECURITY_BOOTSTRAP_USERNAME="$username" \
  LOCKLANE_SECURITY_BOOTSTRAP_PASSWORD="$password" \
    java -jar locklane.jar \
      --locklane.security.seed-only=true \
      --server.port=0 \
      --spring.main.banner-mode=off
) < /dev/null > "$seed_log" 2>&1 || seed_status=$?
unset password password_confirm

case "$seed_status" in
  0)
    account_note="The account '$username' is ready — no password was stored anywhere on disk."
    ;;
  3)
    account_note="An account already existed here, so the username and password you just
entered were NOT applied — sign in with the account you already have."
    ;;
  *)
    echo "error: could not create the login account — the log is at $seed_log" >&2
    exit 1
    ;;
esac
rm -f "$seed_log"

# --- Install a per-user service, or fall back to a detached start (#385) -------
# A detached start only outlives the terminal that launched it: it never comes back
# after a reboot or a crash (#386). A systemd user unit (Linux) or launchd agent
# (macOS) gives it both, restarting it on its own; on any other platform, or if the
# service manager can't be set up (e.g. no session bus), it falls back to #385's
# plain nohup/disown start rather than failing. Output goes to a log file in either
# case, and for the fallback, the pid is recorded so update.sh can find it later.

log_file="$INSTALL_DIR/locklane.log"
pid_file="$INSTALL_DIR/locklane.pid"
service_kind=""

case "$(uname -s)" in
  Linux)
    if command -v systemctl >/dev/null 2>&1; then
      unit_dir="$HOME/.config/systemd/user"
      unit_file="$unit_dir/locklane.service"
      mkdir -p "$unit_dir"
      cat > "$unit_file" <<EOF
[Unit]
Description=locklane
After=network.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$java_bin -jar $INSTALL_DIR/locklane.jar
Restart=always
RestartSec=5
StandardOutput=append:$log_file
StandardError=append:$log_file

[Install]
WantedBy=default.target
EOF
      echo "Installing the systemd user service..."
      if systemctl --user daemon-reload 2>/dev/null && systemctl --user enable --now locklane 2>/dev/null; then
        service_kind="systemd"
        # Best-effort: lets the service start at boot even with nobody logged in.
        loginctl enable-linger "$USER" 2>/dev/null || true
      else
        echo "warning: could not start the systemd user service — falling back to a detached start." >&2
        rm -f "$unit_file"
      fi
    fi
    ;;
  Darwin)
    agent_dir="$HOME/Library/LaunchAgents"
    agent_label="com.locklane.server"
    agent_file="$agent_dir/$agent_label.plist"
    mkdir -p "$agent_dir"
    cat > "$agent_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$agent_label</string>
  <key>ProgramArguments</key>
  <array>
    <string>$java_bin</string>
    <string>-jar</string>
    <string>$INSTALL_DIR/locklane.jar</string>
  </array>
  <key>WorkingDirectory</key><string>$INSTALL_DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key><false/>
  </dict>
  <key>StandardOutPath</key><string>$log_file</string>
  <key>StandardErrorPath</key><string>$log_file</string>
</dict>
</plist>
EOF
    echo "Installing the launchd agent..."
    if launchctl bootstrap "gui/$(id -u)" "$agent_file" 2>/dev/null; then
      service_kind="launchd"
    else
      echo "warning: could not load the launchd agent — falling back to a detached start." >&2
      rm -f "$agent_file"
    fi
    ;;
esac

if [ -z "$service_kind" ]; then
  echo "No per-user service manager available here — starting detached instead; it will not survive a reboot or a crash, so you'll need to restart it yourself."
  echo "Starting the server..."
  (
    cd "$INSTALL_DIR"
    nohup "$java_bin" -jar locklane.jar > "$log_file" 2>&1 < /dev/null &
    echo $! > "$pid_file"
    disown
  )
fi

cat <<EOF

Installed to $INSTALL_DIR:
  locklane.jar
  update.sh
  application-locklane.properties (mode 600; port and origins only)
  locklane.log (server output)

$account_note

The server is running on port $port. Output is logged to $log_file.
EOF

case "$service_kind" in
  systemd)
    cat <<EOF

It's installed as a systemd user service (locklane): it restarts on its own if killed
or if it crashes, and starts again at login or at boot.
  Status:  systemctl --user status locklane
  Stop:    systemctl --user stop locklane
  Remove:  systemctl --user disable --now locklane && rm -f "$unit_file" && systemctl --user daemon-reload
EOF
    ;;
  launchd)
    cat <<EOF

It's installed as a launchd agent ($agent_label): it restarts on its own if killed or
if it crashes, and starts again at login.
  Status:  launchctl print gui/$(id -u)/$agent_label
  Stop:    launchctl bootout gui/$(id -u)/$agent_label
  Remove:  launchctl bootout gui/$(id -u)/$agent_label; rm -f "$agent_file"
EOF
    ;;
esac

cat <<EOF

Later, pull a newer build with:
  $INSTALL_DIR/update.sh
EOF
