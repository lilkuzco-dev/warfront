#!/bin/zsh
# Dev-server lifecycle for headless verification.
#   tools/devserver.sh start   — boot runServer in background, wait for ready, spawn fake player
#   tools/devserver.sh stop    — graceful stop via rcon (fallback: kill the recorded PIDs)
#   tools/devserver.sh status  — report what, if anything, is holding the server port
#   tools/devserver.sh log     — tail the log
#
# Ports are read from run/server.properties, never hardcoded here.
#
# PID discipline (see CLAUDE.md rule 1 — exact PID only, never pattern-match):
#   run/devserver.pid  the launcher (the gradlew wrapper) — what $! gives us
#   run/server.pid     the JVM actually LISTENING on the server port
#
# Those are two different processes, and conflating them is what made an orphaned
# server ambiguous once before: the recorded PID was gradle's, so it could never match
# the PID holding the port, and there was no way to prove a leftover server was ours.
# The listening PID is therefore discovered (via lsof on our own port, once our own log
# has reported the server ready) and recorded separately. It is the only PID this
# script will ever signal to reclaim a port.
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${LOG:-$DIR/run/devserver.log}"
PIDFILE="${PIDFILE:-$DIR/run/devserver.pid}"
SERVER_PIDFILE="${SERVER_PIDFILE:-$DIR/run/server.pid}"
PROPS="$DIR/run/server.properties"
export JAVA_HOME="${JAVA_HOME:-/Users/jessehagy/jdks/jdk-25.0.4+7/Contents/Home}"

prop() {  # prop <key> <default>
  local value=""
  [[ -f "$PROPS" ]] && value="$(grep -E "^$1=" "$PROPS" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]')"
  echo "${value:-$2}"
}
PORT="$(prop server-port 25565)"
RCON_PORT="$(prop rcon.port 25576)"
RCON_PASS="${RCON_PASS:-wartest}"

# PID listening on a TCP port, or empty. Never used to SELECT a process to kill on its
# own — only ever compared against a PID this script recorded.
port_pid() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true }
pid_alive() { [[ -n "$1" && "$1" == <-> ]] && ps -p "$1" -o pid= >/dev/null 2>&1 }
pid_elapsed() { ps -p "$1" -o etime= 2>/dev/null | tr -d ' ' || true }
pid_command() { ps -p "$1" -o command= 2>/dev/null | cut -c1-160 || true }

read_pidfile() {  # read_pidfile <file> -> echoes pid if the file holds a plain number
  [[ -f "$1" ]] || return 0
  local raw="$(tr -d '[:space:]' < "$1")"
  [[ "$raw" == <-> ]] && echo "$raw" || true
}

report_holder() {  # report_holder <pid>
  echo "  PID:      $1"
  echo "  running:  $(pid_elapsed "$1")"
  echo "  command:  $(pid_command "$1")"
}

# Drops a pidfile whose process is gone. Returns 0 if the file was stale and removed.
clean_if_stale() {  # clean_if_stale <file>
  local pid="$(read_pidfile "$1")"
  if [[ -n "$pid" ]] && pid_alive "$pid"; then
    return 1
  fi
  if [[ -e "$1" ]]; then
    rm -f "$1"
    return 0
  fi
  return 1
}

# Signals one exact PID and waits for it to go. Escalates to -9 only for that same PID.
reap() {  # reap <pid> <label>
  local pid="$1" label="$2"
  pid_alive "$pid" || return 0
  echo "  stopping $label (PID $pid)"
  kill "$pid" 2>/dev/null || true
  for _ in {1..20}; do
    pid_alive "$pid" || return 0
    sleep 0.5
  done
  echo "  $label did not exit; escalating to KILL on that same PID"
  kill -9 "$pid" 2>/dev/null || true
  for _ in {1..10}; do
    pid_alive "$pid" || return 0
    sleep 0.5
  done
  return 1
}

remove_pidfiles() { rm -f "$PIDFILE" "$SERVER_PIDFILE" }

# Refuses to start unless the port is free, or is held by a server we can PROVE is ours.
preflight() {
  clean_if_stale "$SERVER_PIDFILE" >/dev/null 2>&1 && echo "cleared a stale $SERVER_PIDFILE (process was gone)" || true
  clean_if_stale "$PIDFILE" >/dev/null 2>&1 && echo "cleared a stale $PIDFILE (process was gone)" || true

  local holder="$(port_pid "$PORT")"
  [[ -z "$holder" ]] && return 0

  local recorded="$(read_pidfile "$SERVER_PIDFILE")"
  if [[ -n "$recorded" && "$recorded" == "$holder" ]]; then
    echo "port $PORT is held by our own server from a previous session (PID $holder) — reclaiming it"
    reap "$holder" "orphaned dev server" || {
      echo "FAILED to stop PID $holder; refusing to continue."; exit 1
    }
    local launcher="$(read_pidfile "$PIDFILE")"
    if [[ -n "$launcher" ]] && pid_alive "$launcher"; then
      # Guard against PID reuse: only signal it if it still looks like the launcher.
      case "$(pid_command "$launcher")" in
        *gradle*|*java*) reap "$launcher" "orphaned launcher" || true ;;
        *) echo "  PID $launcher was reused by an unrelated process; leaving it alone" ;;
      esac
    fi
    remove_pidfiles
    for _ in {1..20}; do
      [[ -z "$(port_pid "$PORT")" ]] && break
      sleep 0.5
    done
    [[ -z "$(port_pid "$PORT")" ]] && return 0
    echo "port $PORT is still held after reclaiming. Refusing to start."; exit 1
  fi

  # Not provably ours. Do NOT touch it, and do NOT quietly move ports.
  echo "REFUSING TO START: port $PORT is held by a process this script cannot prove it owns."
  report_holder "$holder"
  if [[ -z "$recorded" ]]; then
    echo "  reason:   no $SERVER_PIDFILE — this server was not launched by this script"
  else
    echo "  reason:   $SERVER_PIDFILE records PID $recorded, which is not the PID holding the port"
  fi
  echo
  echo "This may be your own Minecraft server or another session's. Stop it yourself, or"
  echo "point this script elsewhere with: server-port in run/server.properties."
  exit 1
}

case "$1" in
start)
  cd "$DIR"
  preflight
  : > "$LOG"
  nohup ./gradlew runServer --console=plain >> "$LOG" 2>&1 &
  launcher_pid=$!
  echo "$launcher_pid" > "$PIDFILE"
  echo "waiting for server on port $PORT..."
  for i in {1..120}; do
    if grep -qE 'Registry loading errors|BUILD FAILED|FAILURE' "$LOG"; then
      echo "SERVER FAILED — log tail:"; tail -30 "$LOG"
      reap "$launcher_pid" "launcher" || true
      remove_pidfiles
      exit 1
    fi
    grep -qE 'Done \([0-9.]+s\)!' "$LOG" && break
    sleep 3
  done
  grep -qE 'Done \([0-9.]+s\)!' "$LOG" || {
    echo "timeout"; tail -20 "$LOG"
    reap "$launcher_pid" "launcher" || true
    remove_pidfiles
    exit 1
  }
  # The server has reported ready, so whatever is on our port now is our JVM. Record
  # that PID: it is the only one a later run is allowed to reclaim the port from.
  server_pid="$(port_pid "$PORT")"
  if [[ -n "$server_pid" ]]; then
    echo "$server_pid" > "$SERVER_PIDFILE"
    echo "server JVM PID $server_pid recorded in $SERVER_PIDFILE"
  else
    echo "WARNING: server reported ready but nothing is listening on $PORT;"
    echo "         no $SERVER_PIDFILE written, so a later run will refuse rather than guess."
  fi
  sleep 2
  node "$DIR/tools/rcon.js" "$RCON_PORT" "$RCON_PASS" "player Watcher spawn" | tail -1
  echo "server ready (fake player Watcher online)"
  ;;
stop)
  # Capture the PID BEFORE asking it to stop, so we can wait on the process itself.
  server_pid="$(read_pidfile "$SERVER_PIDFILE")"
  node "$DIR/tools/rcon.js" "$RCON_PORT" "$RCON_PASS" "stop" 2>/dev/null | head -2 || true
  # Wait for the JVM to EXIT, not merely for the port to free. The listening socket
  # closes at the very start of shutdown, while "Saving worlds" is still running — so
  # waiting on the port and then falling back to kill lands a SIGTERM in the middle of
  # the world save. Give the process a real chance to finish and exit on its own.
  if [[ -n "$server_pid" ]]; then
    for _ in {1..90}; do
      pid_alive "$server_pid" || break
      sleep 1
    done
  else
    for _ in {1..30}; do
      [[ -z "$(port_pid "$PORT")" ]] && break
      sleep 1
    done
  fi
  # Fallback: only the exact PIDs this script recorded are eligible for a signal.
  # Never widen this to pgrep/pkill — the user runs Minecraft on this machine.
  if [[ -n "$server_pid" ]] && pid_alive "$server_pid"; then
    echo "  server did not exit on its own after 90s"
    reap "$server_pid" "server JVM" || true
  fi
  launcher_pid="$(read_pidfile "$PIDFILE")"
  if [[ -n "$launcher_pid" ]] && pid_alive "$launcher_pid"; then
    case "$(pid_command "$launcher_pid")" in
      *gradle*|*java*) reap "$launcher_pid" "launcher" || true ;;
      *) echo "  PID $launcher_pid was reused by an unrelated process; leaving it alone" ;;
    esac
  fi
  remove_pidfiles
  echo "stopped"
  ;;
status)
  holder="$(port_pid "$PORT")"
  recorded="$(read_pidfile "$SERVER_PIDFILE")"
  echo "server port: $PORT   rcon port: $RCON_PORT"
  if [[ -z "$holder" ]]; then
    echo "port $PORT: free"
  else
    echo "port $PORT: held"
    report_holder "$holder"
    if [[ -n "$recorded" && "$recorded" == "$holder" ]]; then
      echo "  owner:    this script (matches $SERVER_PIDFILE)"
    else
      echo "  owner:    NOT ours — start would refuse rather than kill it"
    fi
  fi
  if [[ -n "$recorded" ]] && ! pid_alive "$recorded"; then
    echo "$SERVER_PIDFILE is stale (PID $recorded is gone)"
  fi
  ;;
log)
  tail -40 "$LOG"
  ;;
*)
  echo "usage: devserver.sh start|stop|status|log"; exit 2 ;;
esac
