#!/usr/bin/env bash

set -euo pipefail

runtime="${1:-${BACKEND_RUNTIME:-native}}"
command="${2:-build}"
if [ "$#" -ge 2 ]; then
  shift 2
else
  shift "$#"
fi

case "${runtime}" in
  native|jvm)
    ;;
  *)
    echo "Usage: $0 <native|jvm> <build|up|down> [arguments...]" >&2
    exit 2
    ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
backend_dir="$(cd "${script_dir}/.." && pwd)"
image="buddystudy-backend:${runtime}-local"

case "${command}" in
  build)
    docker build \
      --build-arg "BACKEND_RUNTIME=${runtime}" \
      --tag "${image}" \
      "$@" \
      "${backend_dir}"
    ;;
  up)
    (
      cd "${backend_dir}"
      BACKEND_RUNTIME="${runtime}" docker compose up --build "$@"
    )
    ;;
  down)
    (
      cd "${backend_dir}"
      BACKEND_RUNTIME="${runtime}" docker compose down "$@"
    )
    ;;
  *)
    echo "Usage: $0 <native|jvm> <build|up|down> [arguments...]" >&2
    exit 2
    ;;
esac
