#!/usr/bin/env bash
set -euo pipefail

TUNNEL_ID="${TUNNEL_ID:-24c83c3f-3c20-402f-a9ca-247ca8d25fbb}"

detect_ip() {
  ipconfig getifaddr en0 2>/dev/null || true
}

LAN_IP="${1:-${MACBOOK_AIR_LAN_IP:-$(detect_ip)}}"

if [[ -z "${LAN_IP}" ]]; then
  cat >&2 <<'EOF'
Could not detect the MacBook Air LAN IP.

Pass it explicitly:

  deploy/cloudflared/setup-private-route.sh 192.168.0.10

or:

  MACBOOK_AIR_LAN_IP=192.168.0.10 deploy/cloudflared/setup-private-route.sh
EOF
  exit 1
fi

echo "Registering Cloudflare private route ${LAN_IP}/32 -> ${TUNNEL_ID}"
cloudflared tunnel route ip add "${LAN_IP}/32" "${TUNNEL_ID}"

cat <<EOF

Private route registered.

With Cloudflare WARP enabled, connect clients to:

  MySQL: ${LAN_IP}:3306
  Redis:      ${LAN_IP}:6379

Keep hostname TCP routes only as compatibility fallbacks:

  cloudflared access tcp --hostname db.lowfidev.cloud --url localhost:13306
  cloudflared access tcp --hostname redis.lowfidev.cloud --url localhost:16379
EOF
