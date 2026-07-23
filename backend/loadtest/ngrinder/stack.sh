#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ACTION="${1:-}"
NGRINDER_DATA_DIR="${NGRINDER_DATA_DIR:-${TMPDIR:-/tmp}/buddystudy-ngrinder}"
NGRINDER_WEB_PORT="${NGRINDER_WEB_PORT:-18081}"
NGRINDER_BIND_ADDRESS="${NGRINDER_BIND_ADDRESS:-127.0.0.1}"
export NGRINDER_DATA_DIR NGRINDER_WEB_PORT NGRINDER_BIND_ADDRESS
NGRINDER_CONTROLLER_IMAGE="${NGRINDER_CONTROLLER_IMAGE:-ngrinder/controller:3.5.9-p1}"
NGRINDER_AGENT_IMAGE="${NGRINDER_AGENT_IMAGE:-ngrinder/agent:3.5.9-p1}"

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f "$SCRIPT_DIR/docker-compose.yml" "$@"
  else
    echo "Docker Compose is required." >&2
    exit 1
  fi
}

prepare_images() {
  docker pull ngrinder/controller:3.5.9-p1 >/dev/null
  docker pull ngrinder/agent:3.5.9-p1 >/dev/null
  case "$(uname -m)" in
    arm64|aarch64)
      NGRINDER_CONTROLLER_IMAGE="buddystudy/ngrinder-controller:3.5.9-p1-native-v2"
      NGRINDER_AGENT_IMAGE="buddystudy/ngrinder-agent:3.5.9-p1-native-v3"
      local build_dir="$NGRINDER_DATA_DIR/image-build"
      mkdir -p "$build_dir"
      if ! docker image inspect "$NGRINDER_CONTROLLER_IMAGE" >/dev/null 2>&1; then
        local source_container="buddystudy-ngrinder-war-$$"
        docker create --name "$source_container" ngrinder/controller:3.5.9-p1 >/dev/null
        docker cp \
          "$source_container:/opt/ngrinder-controller-3.5.9-p1.war" \
          "$build_dir/controller.war"
        docker rm "$source_container" >/dev/null
        docker build \
          -f "$SCRIPT_DIR/native/controller.Dockerfile" \
          -t "$NGRINDER_CONTROLLER_IMAGE" \
          "$build_dir" >/dev/null
      fi
      if ! docker image inspect "$NGRINDER_AGENT_IMAGE" >/dev/null 2>&1; then
        docker build \
          -f "$SCRIPT_DIR/native/agent.Dockerfile" \
          -t "$NGRINDER_AGENT_IMAGE" \
          "$SCRIPT_DIR/native" >/dev/null
      fi
      ;;
  esac
  export NGRINDER_CONTROLLER_IMAGE NGRINDER_AGENT_IMAGE
}

case "$ACTION" in
  up)
    mkdir -p "$NGRINDER_DATA_DIR/controller" "$NGRINDER_DATA_DIR/agent"
    prepare_images
    compose up -d
    ;;
  down)
    if [[ "${KEEP_NGRINDER_DATA:-false}" == "true" ]]; then
      compose down --remove-orphans
    else
      compose down --volumes --remove-orphans
    fi
    if [[ "${KEEP_NGRINDER_DATA:-false}" != "true" ]]; then
      rm -rf "$NGRINDER_DATA_DIR"
    fi
    ;;
  logs)
    compose logs --tail=200
    ;;
  *)
    echo "usage: $0 up|down|logs" >&2
    exit 2
    ;;
esac
