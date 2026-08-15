#!/bin/zsh
# Dev-server lifecycle for headless verification.
#   tools/devserver.sh start   — boot runServer in background, wait for ready, spawn fake player
#   tools/devserver.sh stop    — graceful stop via rcon (fallback: kill)
#   tools/devserver.sh log     — tail the log
# Log: $LOG (defaults to warfront/run/devserver.log)
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${LOG:-$DIR/run/devserver.log}"
export JAVA_HOME="${JAVA_HOME:-/Users/jessehagy/jdks/jdk-25.0.4+7/Contents/Home}"

case "$1" in
start)
  cd "$DIR"
  : > "$LOG"
  nohup ./gradlew runServer --console=plain >> "$LOG" 2>&1 &
  echo "waiting for server..."
  for i in {1..120}; do
    if grep -qE 'Registry loading errors|BUILD FAILED|FAILURE' "$LOG"; then
      echo "SERVER FAILED — log tail:"; tail -30 "$LOG"; exit 1
    fi
    grep -qE 'Done \([0-9.]+s\)!' "$LOG" && break
    sleep 3
  done
  grep -qE 'Done \([0-9.]+s\)!' "$LOG" || { echo "timeout"; tail -20 "$LOG"; exit 1; }
  sleep 2
  node "$DIR/tools/rcon.js" 25576 wartest "player Watcher spawn" | tail -1
  echo "server ready (fake player Watcher online)"
  ;;
stop)
  node "$DIR/tools/rcon.js" 25576 wartest "stop" 2>/dev/null | head -2 || true
  sleep 5
  # kill only the dev SERVER — never a running client gametest
  for pid in $(pgrep -f devlaunchinjector 2>/dev/null); do
    ps -o command= -p "$pid" | grep -q "fabric.client.gametest" || kill "$pid" 2>/dev/null || true
  done
  echo "stopped"
  ;;
log)
  tail -40 "$LOG"
  ;;
*)
  echo "usage: devserver.sh start|stop|log"; exit 2 ;;
esac
