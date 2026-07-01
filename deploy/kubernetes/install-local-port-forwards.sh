#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-buddystudy}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-svc/buddystudy-postgres}"
REDIS_SERVICE="${REDIS_SERVICE:-svc/buddystudy-redis-external}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
REDIS_PORT="${REDIS_PORT:-6379}"
LABEL_PREFIX="${LABEL_PREFIX:-com.buddystudy.kubectl-port-forward}"
LOG_DIR="${LOG_DIR:-$HOME/Library/Logs/buddystudy}"
PLIST_DIR="$HOME/Library/LaunchAgents"
TAILSCALE_IP="$(tailscale ip -4 2>/dev/null | head -n 1 || true)"
if [[ -n "${FORWARD_ADDRESS:-}" ]]; then
  BIND_ADDRESS="$FORWARD_ADDRESS"
elif [[ -n "$TAILSCALE_IP" ]]; then
  BIND_ADDRESS="127.0.0.1,$TAILSCALE_IP"
else
  BIND_ADDRESS="127.0.0.1"
fi

mkdir -p "$LOG_DIR" "$PLIST_DIR"

KUBECTL_BIN="$(command -v kubectl)"

write_plist() {
  local name="$1"
  local service="$2"
  local port="$3"
  local label="${LABEL_PREFIX}.${name}"
  local plist="${PLIST_DIR}/${label}.plist"

  cat >"$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${label}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${KUBECTL_BIN}</string>
    <string>-n</string>
    <string>${NAMESPACE}</string>
    <string>port-forward</string>
    <string>${service}</string>
    <string>${port}:${port}</string>
    <string>--address</string>
    <string>${BIND_ADDRESS}</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${LOG_DIR}/${name}.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/${name}.err.log</string>
</dict>
</plist>
EOF

  launchctl bootout "gui/$(id -u)" "$plist" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$(id -u)" "$plist"
  launchctl kickstart -k "gui/$(id -u)/${label}"
  echo "installed ${label}: ${BIND_ADDRESS}:${port} -> ${NAMESPACE}/${service}"
}

write_plist "postgres" "$POSTGRES_SERVICE" "$POSTGRES_PORT"
write_plist "redis" "$REDIS_SERVICE" "$REDIS_PORT"

echo "waiting for forwarded ports..."
for port in "$POSTGRES_PORT" "$REDIS_PORT"; do
  for _ in $(seq 1 20); do
    if nc -z 127.0.0.1 "$port" >/dev/null 2>&1; then
      echo "port ${port} is listening"
      break
    fi
    sleep 0.5
  done
done
