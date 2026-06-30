#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"
kubectl -n buddystuddy delete deployment buddystuddy-redis --ignore-not-found
kubectl apply -k "$SCRIPT_DIR"
