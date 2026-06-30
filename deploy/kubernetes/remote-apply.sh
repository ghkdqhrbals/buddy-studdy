#!/usr/bin/env sh
set -eu

REMOTE_HOST="${1:-gyuminhwangbo@gyumin-macbookair}"
REMOTE_DIR="${2:-/tmp/buddystuddy-kubernetes-deploy}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"

tar -C "$REPO_ROOT" -czf - deploy/kubernetes | ssh "$REMOTE_HOST" "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR' && tar -C '$REMOTE_DIR' -xzf -"
ssh "$REMOTE_HOST" "kubectl apply -k '$REMOTE_DIR/deploy/kubernetes'"
ssh "$REMOTE_HOST" "kubectl -n buddystuddy rollout status deploy/buddystuddy-backend && kubectl -n buddystuddy rollout status deploy/buddystuddy-admin-frontend"

