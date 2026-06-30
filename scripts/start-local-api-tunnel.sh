#!/usr/bin/env bash
set -euo pipefail

LOCAL_BACKEND_URL="${1:-http://localhost:8080}"
CLOUDFLARED_CONFIG="${CLOUDFLARED_CONFIG:-${HOME}/.cloudflared/config.yaml}"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-}"

if [[ -z "${CLOUDFLARED_BIN}" ]]; then
  if command -v cloudflared >/dev/null 2>&1; then
    CLOUDFLARED_BIN="$(command -v cloudflared)"
  elif [[ -x /opt/homebrew/bin/cloudflared ]]; then
    CLOUDFLARED_BIN="/opt/homebrew/bin/cloudflared"
  elif [[ -x /usr/local/bin/cloudflared ]]; then
    CLOUDFLARED_BIN="/usr/local/bin/cloudflared"
  fi
fi

if [[ -z "${CLOUDFLARED_BIN}" ]]; then
  cat >&2 <<'EOF'
cloudflared is not installed.

Install it on macOS:
  brew install cloudflare/cloudflare/cloudflared

Then run:
  scripts/start-local-api-tunnel.sh
EOF
  exit 1
fi

if [[ -f "${CLOUDFLARED_CONFIG}" ]]; then
  echo "Opening named Cloudflare tunnel from ${CLOUDFLARED_CONFIG}"
  echo "Use the hostname configured in that file as BuddyStudy Settings > Developer > Debug API URL."
  exec "${CLOUDFLARED_BIN}" tunnel --config "${CLOUDFLARED_CONFIG}" run
fi

echo "Opening quick Cloudflare tunnel to ${LOCAL_BACKEND_URL}"
echo "Copy the printed https://*.trycloudflare.com URL into BuddyStudy Settings > Developer > Debug API URL."
exec "${CLOUDFLARED_BIN}" tunnel --url "${LOCAL_BACKEND_URL}"
