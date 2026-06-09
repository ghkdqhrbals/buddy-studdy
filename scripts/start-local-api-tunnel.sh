#!/usr/bin/env bash
set -euo pipefail

LOCAL_BACKEND_URL="${1:-http://localhost:8080}"

if ! command -v cloudflared >/dev/null 2>&1; then
  cat >&2 <<'EOF'
cloudflared is not installed.

Install it on macOS:
  brew install cloudflare/cloudflare/cloudflared

Then run:
  scripts/start-local-api-tunnel.sh
EOF
  exit 1
fi

echo "Opening Cloudflare tunnel to ${LOCAL_BACKEND_URL}"
echo "Copy the printed https://*.trycloudflare.com URL into BuddyStuddy Settings > Developer > Debug API URL."
cloudflared tunnel --url "${LOCAL_BACKEND_URL}"
