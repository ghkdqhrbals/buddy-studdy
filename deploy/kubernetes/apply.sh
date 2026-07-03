#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"
kubectl -n buddystudy delete deployment buddystudy-redis buddystudy-redis-proxy buddystudy-redis-stream-coordinator --ignore-not-found
kubectl -n buddystudy delete statefulset buddystudy-redis --ignore-not-found
kubectl -n buddystudy delete service buddystudy-redis buddystudy-redis-external --ignore-not-found
kubectl -n buddystudy delete job buddystudy-redis-cluster-init buddystudy-redis-stream-coordinator-bootstrap --ignore-not-found
kubectl apply -k "$SCRIPT_DIR"
