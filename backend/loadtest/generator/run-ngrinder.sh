#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${BASE_URL:?BASE_URL is required}"
: "${SCENARIO:?SCENARIO is required}"
: "${VUS:?VUS is required}"
: "${SUMMARY_PATH:?SUMMARY_PATH is required}"
: "${TIMESERIES_PATH:?TIMESERIES_PATH is required}"
: "${TELEMETRY_PATH:?TELEMETRY_PATH is required}"
: "${LOG_PATH:?LOG_PATH is required}"

mkdir -p "$(dirname "$SUMMARY_PATH")" "$(dirname "$TIMESERIES_PATH")" \
  "$(dirname "$TELEMETRY_PATH")" "$(dirname "$LOG_PATH")"
python3 "$SCRIPT_DIR/ngrinder/run_test.py" \
  --controller-url "${NGRINDER_CONTROLLER_URL:-http://127.0.0.1:18081}" \
  --base-url "$BASE_URL" \
  --access-token-file "${ACCESS_TOKEN_FILE:-/dev/null}" \
  --scenario "$SCENARIO" \
  --vusers "$VUS" \
  --max-processes "${NGRINDER_MAX_PROCESSES:-4}" \
  --max-threads-per-process "${NGRINDER_MAX_THREADS_PER_PROCESS:-250}" \
  --ramp-seconds "${NGRINDER_RAMP_SECONDS:-30}" \
  --hold-seconds "${NGRINDER_HOLD_SECONDS:-180}" \
  --timeout-ms "${REQUEST_TIMEOUT_MS:-5000}" \
  --studies-limit "${STUDIES_LIMIT:-100}" \
  --validate-body \
  --output "$SUMMARY_PATH" \
  --timeseries-output "$TIMESERIES_PATH" >"$LOG_PATH" 2>&1 &
LOAD_PID=$!

STOP_FILE="${TELEMETRY_PATH}.stop"
rm -f "$STOP_FILE"
python3 "$SCRIPT_DIR/generator/host_telemetry.py" \
  --pid "$LOAD_PID" \
  --output "$TELEMETRY_PATH" \
  --interval "${GENERATOR_TELEMETRY_INTERVAL:-1}" \
  --stop-file "$STOP_FILE" &
TELEMETRY_PID=$!

status=0
wait "$LOAD_PID" || status=$?
touch "$STOP_FILE"
wait "$TELEMETRY_PID" 2>/dev/null || true
rm -f "$STOP_FILE"
exit "$status"
