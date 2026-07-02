#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
K3S_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
KUBECTL="${KUBECTL:-kubectl}"

"$KUBECTL" apply -f "$K3S_DIR/../kubernetes/namespace.yaml"
"$KUBECTL" -n buddystudy delete deployment buddystudy-redis --ignore-not-found
"$KUBECTL" apply -k "$K3S_DIR"

"$KUBECTL" -n buddystudy rollout status statefulset/buddystudy-postgres --timeout=180s
"$KUBECTL" -n buddystudy rollout status statefulset/buddystudy-redis --timeout=240s
"$KUBECTL" -n buddystudy wait --for=condition=complete job/buddystudy-redis-cluster-init --timeout=180s || true
"$KUBECTL" -n buddystudy rollout status deploy/buddystudy-redis-proxy --timeout=180s
"$KUBECTL" -n buddystudy rollout status deploy/buddystudy-backend --timeout=240s
"$KUBECTL" -n buddystudy rollout status deploy/buddystudy-admin-frontend --timeout=180s
