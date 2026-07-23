#!/usr/bin/env bash

set -euo pipefail

readonly HOSTNAME="buddystudy.lowfidev.cloud"
readonly PORT="3011"
readonly LABEL="dev.local.buddystudy-portfolio"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_PLIST="${ROOT_DIR}/deploy/${LABEL}.plist"
readonly TARGET_PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"

for command in npm routingflare cloudflared curl launchctl plutil; do
  command -v "${command}" >/dev/null || {
    echo "Required command is missing: ${command}" >&2
    exit 1
  }
done

dns_tunnel_id="$(
  routingflare settings |
    awk -F': ' '/dns tunnel id:/ { print $2; exit }'
)"
if [[ -z "${dns_tunnel_id}" ]]; then
  echo "Routingflare DNS tunnel is not configured." >&2
  exit 1
fi

cd "${ROOT_DIR}"
npm run build

mkdir -p "${HOME}/Library/LaunchAgents" "${HOME}/Library/Logs"
cp "${SOURCE_PLIST}" "${TARGET_PLIST}"
plutil -lint "${TARGET_PLIST}"

launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
for _ in {1..20}; do
  if ! launchctl print "gui/$(id -u)/${LABEL}" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
launchctl bootstrap "gui/$(id -u)" "${TARGET_PLIST}"

for _ in {1..20}; do
  if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/" >/dev/null; then
    break
  fi
  sleep 1
done
curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/" >/dev/null

if ! routingflare list | grep -Fq "${HOSTNAME} -> 127.0.0.1:${PORT}"; then
  routingflare add dns --host "${HOSTNAME}" --port "${PORT}" --path /
fi

routingflare stop
routingflare start
cloudflared tunnel route dns --overwrite-dns "${dns_tunnel_id}" "${HOSTNAME}"

echo "Routingflare route configured: https://${HOSTNAME} -> 127.0.0.1:${PORT}"
