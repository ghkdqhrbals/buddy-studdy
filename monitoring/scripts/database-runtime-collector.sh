#!/bin/sh
set -eu

interval_seconds="${DATABASE_METRICS_INTERVAL_SECONDS:-30}"
mysql_container="${MYSQL_CONTAINER:-buddystudy-db}"

number_or_null() {
  case "${1:-}" in
    ""|*[!0-9.]*)
      printf 'null'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

while true; do
  captured_at="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
  captured_at_epoch_ms="$(( $(date +%s) * 1000 ))"
  resource_sample="$(
    docker stats --no-stream --format '{{.CPUPerc}}|{{.MemPerc}}' "${mysql_container}" 2>/dev/null \
      | head -n 1 \
      | tr -d '%'
  )"
  cpu_percent=""
  memory_percent=""
  if [ -n "${resource_sample}" ]; then
    previous_ifs="${IFS}"
    IFS='|'
    set -- ${resource_sample}
    IFS="${previous_ifs}"
    cpu_percent="${1:-}"
    memory_percent="${2:-}"
  fi
  connection_sample="$(
    docker exec \
      -e MYSQL_PWD="${MYSQL_PASSWORD}" \
      "${mysql_container}" \
      mysql -N -B -h 127.0.0.1 -u "${MYSQL_USER}" "${MYSQL_DATABASE}" -e \
      "select @@max_connections,
              count(*),
              coalesce(sum(processlist_command <> 'Sleep'), 0)
       from performance_schema.threads
       where type = 'FOREGROUND' and processlist_id is not null;" \
      2>/dev/null \
      | tr '\t' '|' || true
  )"

  max_connections=""
  connections=""
  active_connections=""
  if [ -n "${connection_sample}" ]; then
    previous_ifs="${IFS}"
    IFS='|'
    set -- ${connection_sample}
    IFS="${previous_ifs}"
    max_connections="${1:-}"
    connections="${2:-}"
    active_connections="${3:-}"
  fi
  connection_usage_percent="$(
    awk -v used="${connections:-0}" -v maximum="${max_connections:-0}" \
      'BEGIN { if (maximum > 0) printf "%.4f", used * 100 / maximum; else printf "null" }'
  )"

  printf '%s INFO database_runtime {"capturedAtEpochMs":%s,"databaseCpuPercent":%s,"databaseMemoryPercent":%s,"databaseConnections":%s,"databaseActiveConnections":%s,"databaseMaxConnections":%s,"databaseConnectionUsagePercent":%s}\n' \
    "${captured_at}" \
    "${captured_at_epoch_ms}" \
    "$(number_or_null "${cpu_percent}")" \
    "$(number_or_null "${memory_percent}")" \
    "$(number_or_null "${connections}")" \
    "$(number_or_null "${active_connections}")" \
    "$(number_or_null "${max_connections}")" \
    "${connection_usage_percent}"

  sleep "${interval_seconds}"
done
