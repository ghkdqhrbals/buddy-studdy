#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
K3S_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
KUBECTL="${KUBECTL:-kubectl}"

"$KUBECTL" apply -f "$K3S_DIR/../kubernetes/namespace.yaml"
"$KUBECTL" -n buddystudy delete deployment buddystudy-redis buddystudy-redis-proxy --ignore-not-found
"$KUBECTL" -n buddystudy delete statefulset buddystudy-redis --ignore-not-found
"$KUBECTL" -n buddystudy delete service buddystudy-redis buddystudy-redis-external --ignore-not-found
"$KUBECTL" -n buddystudy delete job buddystudy-redis-cluster-init --ignore-not-found
"$KUBECTL" apply -k "$K3S_DIR"

"$KUBECTL" -n buddystudy rollout status statefulset/buddystudy-mysql --timeout=180s
"$KUBECTL" -n buddystudy rollout status statefulset/buddystudy-redis --timeout=240s
"$KUBECTL" -n buddystudy rollout status deploy/buddystudy-backend --timeout=240s
"$KUBECTL" -n buddystudy rollout status deploy/buddystudy-admin-frontend --timeout=180s
