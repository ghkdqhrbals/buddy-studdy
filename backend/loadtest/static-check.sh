#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash -n \
  "$SCRIPT_DIR/run-comparison.sh" \
  "$SCRIPT_DIR/generator/run-k6.sh" \
  "$SCRIPT_DIR/generator/run-ngrinder.sh" \
  "$SCRIPT_DIR/ngrinder/stack.sh" \
  "$SCRIPT_DIR/ngrinder/native/run-agent.sh"

python3 "$SCRIPT_DIR/validate_scenarios.py" "$SCRIPT_DIR/scenarios.json" \
  --only public-questions,studies,mobile-read-mix
python3 -m py_compile \
  "$SCRIPT_DIR/validate_scenarios.py" \
  "$SCRIPT_DIR/find_saturation.py" \
  "$SCRIPT_DIR/recovery_probe.py" \
  "$SCRIPT_DIR/extract_k6_dashboard.py" \
  "$SCRIPT_DIR/write_metadata.py" \
  "$SCRIPT_DIR/normalize_results.py" \
  "$SCRIPT_DIR/report_results.py" \
  "$SCRIPT_DIR/render_comparison_dashboard.py" \
  "$SCRIPT_DIR/generator/host_telemetry.py" \
  "$SCRIPT_DIR/generator/machine_info.py" \
  "$SCRIPT_DIR/ngrinder/run_test.py" \
  "$SCRIPT_DIR/tests/fixture_server.py"
python3 -m unittest discover -s "$SCRIPT_DIR/tests" -p 'test_*.py'

if docker compose version >/dev/null 2>&1; then
  NGRINDER_DATA_DIR="${TMPDIR:-/tmp}/buddystudy-ngrinder-static" \
  NGRINDER_WEB_PORT=18081 \
  NGRINDER_BIND_ADDRESS=127.0.0.1 \
  docker compose -f "$SCRIPT_DIR/ngrinder/docker-compose.yml" config >/dev/null
elif command -v docker-compose >/dev/null 2>&1; then
  NGRINDER_DATA_DIR="${TMPDIR:-/tmp}/buddystudy-ngrinder-static" \
  NGRINDER_WEB_PORT=18081 \
  NGRINDER_BIND_ADDRESS=127.0.0.1 \
  docker-compose -f "$SCRIPT_DIR/ngrinder/docker-compose.yml" config >/dev/null
else
  echo "Docker Compose is required." >&2
  exit 1
fi

if command -v k6 >/dev/null 2>&1; then
  k6 inspect "$SCRIPT_DIR/k6/api-benchmark.js" >/dev/null
fi
