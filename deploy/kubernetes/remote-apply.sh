#!/usr/bin/env sh
set -eu

REMOTE_HOST="${1:-gyuminhwangbo@gyumin-macbookair}"
REMOTE_DIR="${2:-/tmp/buddystudy-kubernetes-deploy}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"

tar -C "$REPO_ROOT" -czf - deploy/kubernetes | ssh "$REMOTE_HOST" "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR' && tar -C '$REMOTE_DIR' -xzf -"
ssh "$REMOTE_HOST" "KUBECTL=\${KUBECTL:-kubectl}; if ! command -v \"\$KUBECTL\" >/dev/null 2>&1 && [ -x /opt/homebrew/bin/kubectl ]; then KUBECTL=/opt/homebrew/bin/kubectl; fi; \"\$KUBECTL\" apply -f '$REMOTE_DIR/deploy/kubernetes/namespace.yaml'; \"\$KUBECTL\" -n buddystudy delete deployment buddystudy-redis --ignore-not-found; \"\$KUBECTL\" apply -k '$REMOTE_DIR/deploy/kubernetes'"
ssh "$REMOTE_HOST" "KUBECTL=\${KUBECTL:-kubectl}; if ! command -v \"\$KUBECTL\" >/dev/null 2>&1 && [ -x /opt/homebrew/bin/kubectl ]; then KUBECTL=/opt/homebrew/bin/kubectl; fi; \"\$KUBECTL\" -n buddystudy rollout status statefulset/buddystudy-redis; \"\$KUBECTL\" -n buddystudy wait --for=condition=complete job/buddystudy-redis-cluster-init --timeout=180s; \"\$KUBECTL\" -n buddystudy rollout status deploy/buddystudy-backend; \"\$KUBECTL\" -n buddystudy rollout status deploy/buddystudy-admin-frontend"
