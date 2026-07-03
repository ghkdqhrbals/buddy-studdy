#!/usr/bin/env sh
set -eu

REMOTE_HOST="${1:-gyuminhwangbo@gyumin-macbookair}"
REMOTE_DIR="${2:-/tmp/buddystudy-kubernetes-deploy}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
SSH_OPTS="${SSH_OPTS:-}"

tar -C "$REPO_ROOT" -czf - deploy/kubernetes | ssh $SSH_OPTS "$REMOTE_HOST" "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR' && tar -C '$REMOTE_DIR' -xzf -"
ssh $SSH_OPTS "$REMOTE_HOST" "KUBECTL=\${KUBECTL:-kubectl}; if ! command -v \"\$KUBECTL\" >/dev/null 2>&1 && [ -x /opt/homebrew/bin/kubectl ]; then KUBECTL=/opt/homebrew/bin/kubectl; fi; \"\$KUBECTL\" apply -f '$REMOTE_DIR/deploy/kubernetes/namespace.yaml'; \"\$KUBECTL\" -n buddystudy delete deployment buddystudy-redis buddystudy-redis-proxy buddystudy-redis-stream-coordinator --ignore-not-found; \"\$KUBECTL\" -n buddystudy delete statefulset buddystudy-redis-0 buddystudy-redis-1 buddystudy-redis-2 --ignore-not-found; \"\$KUBECTL\" -n buddystudy delete service buddystudy-redis-headless buddystudy-redis-external --ignore-not-found; \"\$KUBECTL\" -n buddystudy delete job buddystudy-redis-cluster-init buddystudy-redis-stream-coordinator-bootstrap --ignore-not-found; \"\$KUBECTL\" apply -k '$REMOTE_DIR/deploy/kubernetes'"
ssh $SSH_OPTS "$REMOTE_HOST" "KUBECTL=\${KUBECTL:-kubectl}; if ! command -v \"\$KUBECTL\" >/dev/null 2>&1 && [ -x /opt/homebrew/bin/kubectl ]; then KUBECTL=/opt/homebrew/bin/kubectl; fi; \"\$KUBECTL\" -n buddystudy rollout status statefulset/buddystudy-redis; \"\$KUBECTL\" -n buddystudy rollout status deploy/buddystudy-backend; \"\$KUBECTL\" -n buddystudy rollout status deploy/buddystudy-admin-frontend"
