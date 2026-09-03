#!/bin/bash
# Claude Code Proxy — start script (persistent studio paths)
# Usage: bash proxy.sh {start|stop|restart|status|logs}
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$DIR/logs"
PID_FILE="$DIR/logs/claude-proxy.pid"
LOG_FILE="$DIR/logs/claude-proxy.log"

mkdir -p "$LOG_DIR"

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

do_start() {
  if is_running; then
    echo "[!] Already running (PID: $(cat "$PID_FILE"))"
    return 0
  fi
  # Re-install deps if missing (studio restarts wipe conda env, not /this_studio)
  python3 -c "import openai" 2>/dev/null || pip install -q "openai>=1.54.0"
  # Load .env
  set -a; source "$DIR/.env"; set +a
  export PORT="${PORT:-4013}" HOST="${HOST:-127.0.0.1}"
  cd "$DIR"
  nohup python3 start_proxy.py > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  sleep 3
  if is_running; then
    echo "[✓] Started (PID: $(cat "$PID_FILE")) — log: $LOG_FILE"
  else
    echo "[✗] Failed to start — tail of log:"
    tail -15 "$LOG_FILE"
    return 1
  fi
}

do_stop() {
  if is_running; then
    kill "$(cat "$PID_FILE")" 2>/dev/null && echo "[✓] Stopped" || echo "[!] Kill failed"
    rm -f "$PID_FILE"
  else
    echo "[!] Not running"
    rm -f "$PID_FILE"
  fi
  pkill -f "claude-code-proxy.*start_proxy" 2>/dev/null || true
  pkill -f "start_proxy.py" 2>/dev/null || true
}

do_status() {
  if is_running; then
    echo "[✓] Running (PID: $(cat "$PID_FILE"))"
  else
    echo "[✗] Stopped"
  fi
  for port in 4013 8788; do
    if ss -tln 2>/dev/null | grep -q ":$port "; then
      echo "    Port $port — listening"
    else
      echo "    Port $port — NOT listening"
    fi
  done
  echo "--- health ---"
  curl -s -m 5 http://127.0.0.1:4013/health || echo "(proxy not responding)"
  echo ""
}

case "${1:-start}" in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; sleep 1; do_start ;;
  status)  do_status ;;
  logs)    tail -f "$LOG_FILE" ;;
  *) echo "Usage: $0 {start|stop|restart|status|logs}"; exit 1 ;;
esac
