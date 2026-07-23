#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${BASE_URL:?BASE_URL is required}"
: "${SCENARIO:?SCENARIO is required}"
: "${TARGET_RPS:?TARGET_RPS is required}"
: "${DURATION:?DURATION is required}"
: "${SUMMARY_PATH:?SUMMARY_PATH is required}"
: "${TIMESERIES_PATH:?TIMESERIES_PATH is required}"
: "${TELEMETRY_PATH:?TELEMETRY_PATH is required}"
: "${DASHBOARD_PATH:?DASHBOARD_PATH is required}"
: "${LOG_PATH:?LOG_PATH is required}"

ACCESS_TOKEN=""
if [[ -n "${ACCESS_TOKEN_FILE:-}" && -f "$ACCESS_TOKEN_FILE" ]]; then
  ACCESS_TOKEN="$(<"$ACCESS_TOKEN_FILE")"
fi
mkdir -p "$(dirname "$SUMMARY_PATH")" "$(dirname "$TIMESERIES_PATH")" \
  "$(dirname "$TELEMETRY_PATH")" "$(dirname "$DASHBOARD_PATH")" "$(dirname "$LOG_PATH")"

K6_WEB_DASHBOARD=true \
K6_WEB_DASHBOARD_OPEN=false \
K6_WEB_DASHBOARD_PERIOD=1s \
K6_WEB_DASHBOARD_EXPORT="$DASHBOARD_PATH" \
BASE_URL="$BASE_URL" \
ACCESS_TOKEN="$ACCESS_TOKEN" \
SCENARIO="$SCENARIO" \
TARGET_RPS="$TARGET_RPS" \
PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-}" \
MAX_VUS="${MAX_VUS:-}" \
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-5s}" \
STUDIES_LIMIT="${STUDIES_LIMIT:-100}" \
VALIDATE_BODY="${VALIDATE_BODY:-false}" \
DURATION="$DURATION" \
SUMMARY_PATH="$SUMMARY_PATH" \
k6 run --quiet "$SCRIPT_DIR/k6/api-benchmark.js" >"$LOG_PATH" 2>&1 &
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
python3 "$SCRIPT_DIR/extract_k6_dashboard.py" \
  --input "$DASHBOARD_PATH" \
  --output "$TIMESERIES_PATH"
exit "$status"
