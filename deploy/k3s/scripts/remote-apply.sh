#!/usr/bin/env sh
set -eu

REMOTE_HOST="${1:?usage: deploy/k3s/scripts/remote-apply.sh user@linux-host [remote-dir]}"
REMOTE_DIR="${2:-/tmp/buddystudy-k3s-deploy}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)"
SSH_OPTS="${SSH_OPTS:-}"

tar -C "$REPO_ROOT" -czf - deploy/kubernetes deploy/k3s | ssh $SSH_OPTS "$REMOTE_HOST" "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR' && tar -C '$REMOTE_DIR' -xzf -"
ssh $SSH_OPTS "$REMOTE_HOST" "cd '$REMOTE_DIR' && KUBECTL=\${KUBECTL:-kubectl} deploy/k3s/scripts/apply.sh"
