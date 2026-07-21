#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$BACKEND_DIR/.." && pwd)"

MVC_REF="${MVC_REF:-eca7e320}"
WEBFLUX_REF="${WEBFLUX_REF:-HEAD}"
ROUNDS="${ROUNDS:-3}"
VUS="${VUS:-50}"
DURATION="${DURATION:-30s}"
WARMUP_DURATION="${WARMUP_DURATION:-10s}"
JVM_HEAP="${JVM_HEAP:-512m}"
JVM_CPU_COUNT="${JVM_CPU_COUNT:-4}"
DB_POOL_MAX="${DB_POOL_MAX:-10}"
BLOCKING_MAX_SIZE="${BLOCKING_MAX_SIZE:-16}"
BLOCKING_QUEUE_CAPACITY="${BLOCKING_QUEUE_CAPACITY:-64}"
BENCHMARK_LOGGING="${BENCHMARK_LOGGING:-OFF}"
TELEMETRY_INTERVAL="${TELEMETRY_INTERVAL:-2}"
ENABLE_JFR="${ENABLE_JFR:-true}"
ENABLE_NMT="${ENABLE_NMT:-true}"
MIN_FREE_DISK_MB="${MIN_FREE_DISK_MB:-4096}"
RESULTS_DIR="${RESULTS_DIR:-$SCRIPT_DIR/results/$(date -u +%Y%m%dT%H%M%SZ)}"

if [[ -n "${BENCHMARK_JAVA_BIN:-}" ]]; then
  JAVA_BIN="$BENCHMARK_JAVA_BIN"
elif [[ -x /usr/libexec/java_home ]]; then
  JAVA_BIN="$(/usr/libexec/java_home -v 25)/bin/java"
else
  JAVA_BIN="$(command -v java)"
fi
JAVA_HOME_DIR="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
JCMD_BIN="$JAVA_HOME_DIR/bin/jcmd"
JFR_BIN="$JAVA_HOME_DIR/bin/jfr"

POSTGRES_CONTAINER="buddystudy-loadtest-postgres"
REDIS_CONTAINER="buddystudy-loadtest-redis"
POSTGRES_PORT="${POSTGRES_PORT:-55432}"
REDIS_PORT="${REDIS_PORT:-56379}"
APP_PORT="${APP_PORT:-18080}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buddystudy-loadtest.XXXXXX")"
LOCK_DIR="${TMPDIR:-/tmp}/buddystudy-loadtest.lock"
APP_PID=""
TELEMETRY_PID=""
LOAD_PID=""

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

for command in docker git curl jq k6; do
  require_command "$command"
done

if [[ ! -x "$JAVA_BIN" ]]; then
  echo "Benchmark Java executable was not found: $JAVA_BIN" >&2
  exit 1
fi

mkdir -p "$RESULTS_DIR/raw" "$RESULTS_DIR/logs" "$RESULTS_DIR/telemetry" "$RESULTS_DIR/jfr" "$RESULTS_DIR/diagnostics"

stop_telemetry() {
  if [[ -n "$TELEMETRY_PID" ]] && kill -0 "$TELEMETRY_PID" 2>/dev/null; then
    kill -TERM "$TELEMETRY_PID" 2>/dev/null || true
    wait "$TELEMETRY_PID" 2>/dev/null || true
  fi
  TELEMETRY_PID=""
}

cleanup_app() {
  stop_telemetry
  if [[ -n "$LOAD_PID" ]] && kill -0 "$LOAD_PID" 2>/dev/null; then
    kill -TERM "$LOAD_PID" 2>/dev/null || true
    wait "$LOAD_PID" 2>/dev/null || true
  fi
  LOAD_PID=""
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  APP_PID=""
}

cleanup() {
  cleanup_app
  docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  if [[ -d "$WORK_DIR/mvc" ]]; then
    git -C "$REPO_DIR" worktree remove --force "$WORK_DIR/mvc" >/dev/null 2>&1 || true
  fi
  if [[ -d "$WORK_DIR/webflux" ]]; then
    git -C "$REPO_DIR" worktree remove --force "$WORK_DIR/webflux" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK_DIR"
  rmdir "$LOCK_DIR" >/dev/null 2>&1 || true
}
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "Another BuddyStudy load test is already running ($LOCK_DIR)." >&2
  rm -rf "$WORK_DIR"
  exit 1
fi
trap cleanup EXIT INT TERM

start_dependencies() {
  docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$POSTGRES_CONTAINER" \
    -p "$POSTGRES_PORT:5432" \
    -e POSTGRES_DB=buddystudy \
    -e POSTGRES_USER=buddystudy \
    -e POSTGRES_PASSWORD=benchmark-password \
    postgres:16-alpine >/dev/null
  docker run -d --name "$REDIS_CONTAINER" -p "$REDIS_PORT:6379" redis:7-alpine >/dev/null

  for _ in $(seq 1 60); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready -U buddystudy -d buddystudy >/dev/null 2>&1 && \
       docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q PONG; then
      return
    fi
    sleep 1
  done
  echo "Benchmark dependencies did not become ready." >&2
  exit 1
}

reset_dependencies() {
  docker exec "$POSTGRES_CONTAINER" psql -v ON_ERROR_STOP=1 -U buddystudy -d buddystudy \
    -c 'drop schema public cascade; create schema public;' >/dev/null
  docker exec "$REDIS_CONTAINER" redis-cli FLUSHALL >/dev/null
}

prepare_worktree() {
  local name="$1"
  local ref="$2"
  local directory="$WORK_DIR/$name"
  git -C "$REPO_DIR" worktree add --detach "$directory" "$ref" >/dev/null
  echo "$directory"
}

build_jar() {
  local source_dir="$1"
  local jar
  if ! (cd "$source_dir/backend" && ./gradlew :tutor:bootJar --no-daemon >/dev/null); then
    echo "Backend JAR build failed for $source_dir" >&2
    return 1
  fi
  jar="$(find "$source_dir/backend/tutor/build/libs" -maxdepth 1 -name 'tutor-*.jar' ! -name '*-plain.jar' -print -quit)"
  if [[ -z "$jar" || ! -s "$jar" ]]; then
    echo "Backend JAR was not produced for $source_dir" >&2
    return 1
  fi
  echo "$jar"
}

check_free_disk() {
  local available_kb
  available_kb="$(df -Pk "$WORK_DIR" | awk 'NR == 2 { print $4 }')"
  if [[ -z "$available_kb" || "$available_kb" -lt $((MIN_FREE_DISK_MB * 1024)) ]]; then
    echo "Load test requires at least ${MIN_FREE_DISK_MB} MiB free disk space; available: $((available_kb / 1024)) MiB." >&2
    exit 1
  fi
}

start_app() {
  local runtime="$1"
  local round="$2"
  local jar="$3"
  local log_file="$RESULTS_DIR/logs/${runtime}-round${round}.log"
  cleanup_app

  local -a jvm_options=(-Xms"$JVM_HEAP" -Xmx"$JVM_HEAP" -XX:ActiveProcessorCount="$JVM_CPU_COUNT")
  if [[ "$ENABLE_NMT" == "true" ]]; then
    jvm_options+=(-XX:NativeMemoryTracking=summary)
  fi

  env \
    APP_PORT="$APP_PORT" \
    SPRING_PROFILES_ACTIVE=benchmark \
    DATABASE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/buddystudy" \
    DATABASE_USERNAME=buddystudy \
    DATABASE_PASSWORD=benchmark-password \
    DB_POOL_MAX="$DB_POOL_MAX" \
    SERVER_TOMCAT_THREADS_MAX="$BLOCKING_MAX_SIZE" \
    SERVER_TOMCAT_THREADS_MIN_SPARE="$BLOCKING_MAX_SIZE" \
    SERVER_TOMCAT_ACCEPT_COUNT="$BLOCKING_QUEUE_CAPACITY" \
    SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true \
    REDIS_HOST=127.0.0.1 \
    REDIS_PORT="$REDIS_PORT" \
    FLYWAY_ENABLED=true \
    SCHEDULER_ENABLED=false \
    REACTION_STREAM_ENABLED=false \
    ENABLE_OPENAPI_DOCS=false \
    WEBFLUX_BLOCKING_CORE_SIZE="$BLOCKING_MAX_SIZE" \
    WEBFLUX_BLOCKING_MAX_SIZE="$BLOCKING_MAX_SIZE" \
    WEBFLUX_BLOCKING_QUEUE_CAPACITY="$BLOCKING_QUEUE_CAPACITY" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics \
    LOGGING_LEVEL_COM_BUDDYSTUDY_BACKEND_COMMON_ADAPTER_INBOUND_WEB_REQUESTLOGGINGFILTER="$BENCHMARK_LOGGING" \
    "$JAVA_BIN" "${jvm_options[@]}" -jar "$jar" \
    >"$log_file" 2>&1 &
  APP_PID=$!

  for _ in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$APP_PORT/health" >/dev/null 2>&1; then
      return
    fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "$runtime failed to start. See $log_file" >&2
      tail -n 80 "$log_file" >&2
      exit 1
    fi
    sleep 1
  done
  echo "$runtime did not become ready. See $log_file" >&2
  exit 1
}

seed_data() {
  local registration
  registration="$(curl -fsS -X POST "http://127.0.0.1:$APP_PORT/api/v1/devices/register" \
    -H 'Content-Type: application/json' \
    -d '{"platform":"ios","language":"ko","timezone":"Asia/Seoul","apnsEnvironment":"sandbox","apnsToken":""}')"
  ACCESS_TOKEN="$(jq -er '.accessToken' <<<"$registration")"
  DEVICE_ID="$(jq -er '.deviceId' <<<"$registration")"

  docker exec -i "$POSTGRES_CONTAINER" psql -v ON_ERROR_STOP=1 \
    -v "device_id=$DEVICE_ID" -U buddystudy -d buddystudy \
    < "$SCRIPT_DIR/fixtures/seed.sql" >/dev/null
}

run_scenario() {
  local runtime="$1"
  local round="$2"
  local scenario="$3"
  local prefix="$RESULTS_DIR/raw/${runtime}-round${round}-${scenario}"
  local diagnostic_prefix="$RESULTS_DIR/diagnostics/${runtime}-round${round}-${scenario}"
  local telemetry_file="$RESULTS_DIR/telemetry/${runtime}-round${round}-${scenario}.jsonl"
  local recording_name="${runtime}_round${round}_${scenario//-/_}"
  local jfr_file="$RESULTS_DIR/jfr/${runtime}-round${round}-${scenario}.jfr"

  BASE_URL="http://127.0.0.1:$APP_PORT" \
    ACCESS_TOKEN="$ACCESS_TOKEN" \
    SCENARIO="$scenario" \
    VUS="$VUS" \
    DURATION="$WARMUP_DURATION" \
    SUMMARY_PATH="$prefix-warmup.json" \
    k6 run --quiet "$SCRIPT_DIR/k6/api-benchmark.js" >/dev/null

  if [[ "$ENABLE_NMT" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" VM.native_memory baseline >"$diagnostic_prefix-nmt-baseline.txt" 2>&1 || true
  fi
  if [[ "$ENABLE_JFR" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" JFR.start \
      name="$recording_name" settings=profile filename="$jfr_file" \
      >"$diagnostic_prefix-jfr-start.txt" 2>&1 || true
  fi

  local k6_status=0
  BASE_URL="http://127.0.0.1:$APP_PORT" \
    ACCESS_TOKEN="$ACCESS_TOKEN" \
    SCENARIO="$scenario" \
    VUS="$VUS" \
    DURATION="$DURATION" \
    SUMMARY_PATH="$prefix.json" \
    k6 run --quiet "$SCRIPT_DIR/k6/api-benchmark.js" >/dev/null &
  LOAD_PID=$!

  python3 "$SCRIPT_DIR/telemetry.py" \
    --pid "$APP_PID" \
    --load-generator-pid "$LOAD_PID" \
    --base-url "http://127.0.0.1:$APP_PORT" \
    --output "$telemetry_file" \
    --interval "$TELEMETRY_INTERVAL" \
    --postgres-container "$POSTGRES_CONTAINER" \
    --redis-container "$REDIS_CONTAINER" &
  TELEMETRY_PID=$!

  wait "$LOAD_PID" || k6_status=$?
  LOAD_PID=""

  stop_telemetry
  if [[ "$ENABLE_JFR" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" JFR.stop name="$recording_name" \
      >"$diagnostic_prefix-jfr-stop.txt" 2>&1 || true
    if [[ -s "$jfr_file" && -x "$JFR_BIN" ]]; then
      "$JFR_BIN" summary "$jfr_file" >"$diagnostic_prefix-jfr-summary.txt" 2>&1 || true
    fi
  fi
  if [[ "$ENABLE_NMT" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" VM.native_memory summary.diff scale=KB \
      >"$diagnostic_prefix-nmt-diff.txt" 2>&1 || true
  fi
  if [[ -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" Thread.print -l >"$diagnostic_prefix-threads.txt" 2>&1 || true
    "$JCMD_BIN" "$APP_PID" GC.heap_info >"$diagnostic_prefix-heap.txt" 2>&1 || true
  fi
  return "$k6_status"
}

run_runtime() {
  local runtime="$1"
  local round="$2"
  local jar="$3"
  echo "[$runtime round $round] resetting database"
  reset_dependencies
  start_app "$runtime" "$round" "$jar"
  seed_data

  for scenario in health public-questions studies; do
    echo "[$runtime round $round] $scenario"
    run_scenario "$runtime" "$round" "$scenario"
  done

  ps -o rss= -p "$APP_PID" | awk '{ print $1 }' > "$RESULTS_DIR/raw/${runtime}-round${round}-rss-kb.txt"
  cleanup_app
  sleep 2
}

write_report() {
  python3 "$SCRIPT_DIR/summarize.py" "$RESULTS_DIR" \
    --mvc-ref "$MVC_COMMIT" \
    --webflux-ref "$WEBFLUX_COMMIT" \
    --rounds "$ROUNDS" \
    --vus "$VUS" \
    --duration "$DURATION" \
    --heap "$JVM_HEAP" \
    --cpu-count "$JVM_CPU_COUNT" \
    --db-pool "$DB_POOL_MAX" \
    --blocking-concurrency "$BLOCKING_MAX_SIZE" \
    --logging "$BENCHMARK_LOGGING" \
    --telemetry-interval "$TELEMETRY_INTERVAL" \
    --jfr "$ENABLE_JFR" \
    --nmt "$ENABLE_NMT"
}

require_command python3
check_free_disk
start_dependencies

echo "Preparing MVC $MVC_REF"
MVC_DIR="$(prepare_worktree mvc "$MVC_REF")"
MVC_COMMIT="$(git -C "$MVC_DIR" rev-parse HEAD)"
MVC_JAR="$(build_jar "$MVC_DIR")"

echo "Preparing WebFlux $WEBFLUX_REF"
WEBFLUX_DIR="$(prepare_worktree webflux "$WEBFLUX_REF")"
WEBFLUX_COMMIT="$(git -C "$WEBFLUX_DIR" rev-parse HEAD)"
WEBFLUX_JAR="$(build_jar "$WEBFLUX_DIR")"

for round in $(seq 1 "$ROUNDS"); do
  if (( round % 2 == 1 )); then
    run_runtime mvc "$round" "$MVC_JAR"
    run_runtime webflux "$round" "$WEBFLUX_JAR"
  else
    run_runtime webflux "$round" "$WEBFLUX_JAR"
    run_runtime mvc "$round" "$MVC_JAR"
  fi
done

write_report
echo "Benchmark report: $RESULTS_DIR/REPORT.md"
