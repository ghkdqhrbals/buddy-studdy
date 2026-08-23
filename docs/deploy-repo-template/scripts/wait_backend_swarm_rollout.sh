#!/usr/bin/env bash
set -euo pipefail

service_name="${1:?service name is required}"
expected_image="${2:?expected image is required}"
timeout_seconds="${3:-600}"
poll_seconds="${SWARM_ROLLOUT_POLL_SECONDS:-2}"
started_at="$(date +%s)"
last_snapshot=""

normalize_image() {
  printf '%s' "${1%%@sha256:*}"
}

print_diagnostics() {
  local update_state update_message replicas service_image
  update_state="$(docker service inspect --format '{{if .UpdateStatus}}{{.UpdateStatus.State}}{{else}}none{{end}}' "${service_name}" 2>/dev/null || true)"
  update_message="$(docker service inspect --format '{{if .UpdateStatus}}{{.UpdateStatus.Message}}{{else}}No update has been recorded.{{end}}' "${service_name}" 2>/dev/null || true)"
  replicas="$(docker service ls --filter "name=${service_name}" --format '{{.Replicas}}' 2>/dev/null | head -n 1)"
  service_image="$(docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${service_name}" 2>/dev/null || true)"

  echo "Service: ${service_name}"
  echo "Expected image: ${expected_image}"
  echo "Service image: ${service_image:-unknown}"
  echo "Replicas: ${replicas:-unknown}"
  echo "Update state: ${update_state:-unknown}"
  echo "Update message: ${update_message:-unknown}"
  echo "Task history:"
  docker service ps \
    --no-trunc \
    --format 'table {{.ID}}\t{{.Name}}\t{{.Image}}\t{{.DesiredState}}\t{{.CurrentState}}\t{{.Error}}' \
    "${service_name}" 2>/dev/null || true
}

if ! docker service inspect "${service_name}" >/dev/null 2>&1; then
  echo "Swarm service does not exist: ${service_name}" >&2
  exit 1
fi

expected_image_normalized="$(normalize_image "${expected_image}")"

while true; do
  update_state="$(docker service inspect --format '{{if .UpdateStatus}}{{.UpdateStatus.State}}{{else}}none{{end}}' "${service_name}")"
  update_message="$(docker service inspect --format '{{if .UpdateStatus}}{{.UpdateStatus.Message}}{{else}}No update has been recorded.{{end}}' "${service_name}")"
  service_image="$(docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${service_name}")"
  replicas="$(docker service ls --filter "name=${service_name}" --format '{{.Replicas}}' | head -n 1)"
  expected_task_running=false

  while IFS='|' read -r task_image current_state; do
    if [ "$(normalize_image "${task_image}")" = "${expected_image_normalized}" ]; then
      case "${current_state}" in
        Running*) expected_task_running=true ;;
      esac
    fi
  done < <(
    docker service ps \
      --no-trunc \
      --filter desired-state=running \
      --format '{{.Image}}|{{.CurrentState}}' \
      "${service_name}"
  )

  snapshot="${update_state}|${replicas}|${service_image}|${expected_task_running}|${update_message}"
  if [ "${snapshot}" != "${last_snapshot}" ]; then
    echo "Swarm rollout: state=${update_state} replicas=${replicas:-unknown} expectedTaskRunning=${expected_task_running} message=${update_message}"
    last_snapshot="${snapshot}"
  fi

  case "${update_state}" in
    paused|rollback_started|rollback_paused|rollback_completed)
      echo "Swarm rollout failed: ${update_state} (${update_message})" >&2
      print_diagnostics >&2
      exit 1
      ;;
  esac

  if [ "$(normalize_image "${service_image}")" != "${expected_image_normalized}" ]; then
    echo "Swarm service image does not match the requested release." >&2
    print_diagnostics >&2
    exit 1
  fi

  if { [ "${update_state}" = "completed" ] || [ "${update_state}" = "none" ]; } \
    && [ "${replicas}" = "1/1" ] \
    && [ "${expected_task_running}" = "true" ]; then
    echo "Swarm rollout completed for ${expected_image}."
    break
  fi

  now="$(date +%s)"
  if [ $((now - started_at)) -ge "${timeout_seconds}" ]; then
    echo "Timed out after ${timeout_seconds}s waiting for the Swarm rollout." >&2
    print_diagnostics >&2
    exit 1
  fi

  sleep "${poll_seconds}"
done
