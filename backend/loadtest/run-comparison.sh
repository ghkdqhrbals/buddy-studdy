#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$BACKEND_DIR/.." && pwd)"

TOOL="${TOOL:-all}"
PROFILE="${PROFILE:-standard}"
MVC_REF="${MVC_REF:-eca7e320}"
WEBFLUX_REF="${WEBFLUX_REF:-HEAD}"
SCENARIO_LIST="${SCENARIOS:-${SCENARIO_LIST:-public-questions,studies}}"
TARGET_HOST="${TARGET_HOST:-}"
LOAD_GENERATOR_SSH="${LOAD_GENERATOR_SSH:-}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-5s}"
REQUEST_TIMEOUT_MS="${REQUEST_TIMEOUT_MS:-5000}"
STUDIES_LIMIT="${STUDIES_LIMIT:-100}"
PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-}"
MAX_VUS="${MAX_VUS:-}"
RESTART_APP_PER_STAGE="${RESTART_APP_PER_STAGE:-false}"
JVM_HEAP="${JVM_HEAP:-512m}"
JVM_CPU_COUNT="${JVM_CPU_COUNT:-4}"
DB_POOL_MAX="${DB_POOL_MAX:-10}"
BLOCKING_MAX_SIZE="${BLOCKING_MAX_SIZE:-16}"
BLOCKING_QUEUE_CAPACITY="${BLOCKING_QUEUE_CAPACITY:-64}"
BENCHMARK_LOGGING="${BENCHMARK_LOGGING:-OFF}"
TELEMETRY_INTERVAL="${TELEMETRY_INTERVAL:-2}"
GENERATOR_TELEMETRY_INTERVAL="${GENERATOR_TELEMETRY_INTERVAL:-1}"
GENERATOR_NETWORK_CAPACITY_MBPS="${GENERATOR_NETWORK_CAPACITY_MBPS:-0}"
MIN_FREE_DISK_MB="${MIN_FREE_DISK_MB:-4096}"
MAX_CLOCK_SKEW_MS="${MAX_CLOCK_SKEW_MS:-2000}"
AUTO_FINE_SWEEP="${AUTO_FINE_SWEEP:-true}"
KEEP_NGRINDER="${KEEP_NGRINDER:-false}"
MAX_CONCURRENT_USERS="${MAX_CONCURRENT_USERS:-1000}"
NGRINDER_MAX_PROCESSES="${NGRINDER_MAX_PROCESSES:-4}"
NGRINDER_MAX_THREADS_PER_PROCESS="${NGRINDER_MAX_THREADS_PER_PROCESS:-250}"

case "$PROFILE" in
  smoke)
    ROUNDS="${ROUNDS:-1}"
    TARGET_RPS_LIST="${TARGET_RPS_LIST:-5}"
    DURATION="${DURATION:-5s}"
    WARMUP_DURATION="${WARMUP_DURATION:-2s}"
    NGRINDER_VUS_LIST="${NGRINDER_VUS_LIST:-1}"
    NGRINDER_RAMP_SECONDS="${NGRINDER_RAMP_SECONDS:-0}"
    NGRINDER_HOLD_SECONDS="${NGRINDER_HOLD_SECONDS:-5}"
    STAGE_COOLDOWN_SECONDS="${STAGE_COOLDOWN_SECONDS:-1}"
    ENABLE_JFR="${ENABLE_JFR:-false}"
    ENABLE_NMT="${ENABLE_NMT:-false}"
    AUTO_FINE_SWEEP=false
    ;;
  standard)
    ROUNDS="${ROUNDS:-3}"
    TARGET_RPS_LIST="${TARGET_RPS_LIST:-1000,1500,2000,2500,3000}"
    DURATION="${DURATION:-60s}"
    WARMUP_DURATION="${WARMUP_DURATION:-10s}"
    NGRINDER_VUS_LIST="${NGRINDER_VUS_LIST:-25,50,100,200,400,600,800,1000}"
    NGRINDER_RAMP_SECONDS="${NGRINDER_RAMP_SECONDS:-30}"
    NGRINDER_HOLD_SECONDS="${NGRINDER_HOLD_SECONDS:-180}"
    STAGE_COOLDOWN_SECONDS="${STAGE_COOLDOWN_SECONDS:-60}"
    ENABLE_JFR="${ENABLE_JFR:-false}"
    ENABLE_NMT="${ENABLE_NMT:-false}"
    ;;
  diagnostic)
    ROUNDS="${ROUNDS:-1}"
    TARGET_RPS_LIST="${TARGET_RPS_LIST:-2000}"
    DURATION="${DURATION:-60s}"
    WARMUP_DURATION="${WARMUP_DURATION:-10s}"
    NGRINDER_VUS_LIST="${NGRINDER_VUS_LIST:-200}"
    NGRINDER_RAMP_SECONDS="${NGRINDER_RAMP_SECONDS:-30}"
    NGRINDER_HOLD_SECONDS="${NGRINDER_HOLD_SECONDS:-180}"
    STAGE_COOLDOWN_SECONDS="${STAGE_COOLDOWN_SECONDS:-30}"
    ENABLE_JFR="${ENABLE_JFR:-true}"
    ENABLE_NMT="${ENABLE_NMT:-true}"
    AUTO_FINE_SWEEP=false
    ;;
  soak)
    ROUNDS="${ROUNDS:-1}"
    SUSTAINABLE_RPS="${SUSTAINABLE_RPS:-1000}"
    TARGET_RPS_LIST="${TARGET_RPS_LIST:-$((SUSTAINABLE_RPS * 70 / 100))}"
    DURATION="${DURATION:-15m}"
    WARMUP_DURATION="${WARMUP_DURATION:-30s}"
    NGRINDER_VUS_LIST="${NGRINDER_VUS_LIST:-${SOAK_VUS:-100}}"
    NGRINDER_RAMP_SECONDS="${NGRINDER_RAMP_SECONDS:-30}"
    NGRINDER_HOLD_SECONDS="${NGRINDER_HOLD_SECONDS:-900}"
    STAGE_COOLDOWN_SECONDS="${STAGE_COOLDOWN_SECONDS:-60}"
    ENABLE_JFR="${ENABLE_JFR:-false}"
    ENABLE_NMT="${ENABLE_NMT:-false}"
    AUTO_FINE_SWEEP=false
    ;;
  *)
    echo "PROFILE must be smoke, standard, diagnostic, or soak: $PROFILE" >&2
    exit 1
    ;;
esac

case "$TOOL" in
  k6|ngrinder|all) ;;
  *)
    echo "TOOL must be k6, ngrinder, or all: $TOOL" >&2
    exit 1
    ;;
esac

RESULTS_DIR="${RESULTS_DIR:-$SCRIPT_DIR/results/$(date -u +%Y%m%dT%H%M%SZ)-$PROFILE}"
IFS=',' read -r -a TARGET_RATES <<<"$TARGET_RPS_LIST"
IFS=',' read -r -a NGRINDER_VUS <<<"$NGRINDER_VUS_LIST"
IFS=',' read -r -a SCENARIOS_ARRAY <<<"$SCENARIO_LIST"

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

MYSQL_CONTAINER="buddystudy-loadtest-mysql"
REDIS_CONTAINER="buddystudy-loadtest-redis"
MYSQL_PORT="${MYSQL_PORT:-53306}"
REDIS_PORT="${REDIS_PORT:-56379}"
APP_PORT="${APP_PORT:-18080}"
TARGET_BASE_URL="${TARGET_HOST:-http://127.0.0.1:$APP_PORT}"
NGRINDER_TARGET_BASE_URL="${NGRINDER_TARGET_HOST:-$TARGET_BASE_URL}"
if [[ -z "$LOAD_GENERATOR_SSH" ]]; then
  NGRINDER_TARGET_BASE_URL="${NGRINDER_TARGET_BASE_URL/127.0.0.1/host.docker.internal}"
  NGRINDER_TARGET_BASE_URL="${NGRINDER_TARGET_BASE_URL/localhost/host.docker.internal}"
fi
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buddystudy-loadtest.XXXXXX")"
LOCK_DIR="${TMPDIR:-/tmp}/buddystudy-loadtest.lock"
REMOTE_LOADTEST_DIR="${REMOTE_LOADTEST_DIR:-/tmp/buddystudy-loadtest-${USER:-runner}-$$}"
NGRINDER_DATA_DIR="$WORK_DIR/ngrinder"
LOADTEST_DOCKER_CONFIG="$WORK_DIR/docker-config"
APP_PID=""
TELEMETRY_PID=""
LOAD_PID=""
ACCESS_TOKEN=""
DEVICE_ID=""
NGRINDER_STARTED=false
REMOTE_PREPARED=false

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

validate_inputs() {
  local value
  for value in "${TARGET_RATES[@]}" "${NGRINDER_VUS[@]}"; do
    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
      echo "Load levels must be positive integers: $value" >&2
      exit 1
    fi
  done
  if [[ ! "$ROUNDS" =~ ^[1-9][0-9]*$ || ! "$STUDIES_LIMIT" =~ ^[1-9][0-9]*$ ]]; then
    echo "ROUNDS and STUDIES_LIMIT must be positive integers." >&2
    exit 1
  fi
  for value in "$MAX_CONCURRENT_USERS" "$NGRINDER_MAX_PROCESSES" "$NGRINDER_MAX_THREADS_PER_PROCESS"; do
    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
      echo "nGrinder concurrency limits must be positive integers: $value" >&2
      exit 1
    fi
  done
  for value in "${NGRINDER_VUS[@]}"; do
    if (( value > MAX_CONCURRENT_USERS )); then
      echo "nGrinder VUser stage $value exceeds MAX_CONCURRENT_USERS=$MAX_CONCURRENT_USERS." >&2
      exit 1
    fi
  done
  python3 "$SCRIPT_DIR/validate_scenarios.py" "$SCRIPT_DIR/scenarios.json" \
    --only "$SCENARIO_LIST"
  case "$TARGET_BASE_URL" in
    *api.ghkdqhrbals.org*|*lowfidev.cloud*)
      echo "Production targets are forbidden: $TARGET_BASE_URL" >&2
      exit 1
      ;;
  esac
}

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

stop_ngrinder() {
  if [[ "$NGRINDER_STARTED" != "true" || "$KEEP_NGRINDER" == "true" ]]; then
    return
  fi
  if [[ -n "$LOAD_GENERATOR_SSH" ]]; then
    ssh "$LOAD_GENERATOR_SSH" \
      "DOCKER_CONFIG='$REMOTE_LOADTEST_DIR/generator/docker-config' \
      NGRINDER_DATA_DIR='$REMOTE_LOADTEST_DIR/ngrinder-data' \
      '$REMOTE_LOADTEST_DIR/ngrinder/stack.sh' down" \
      >/dev/null 2>&1 || true
  else
    NGRINDER_DATA_DIR="$NGRINDER_DATA_DIR" "$SCRIPT_DIR/ngrinder/stack.sh" down \
      >/dev/null 2>&1 || true
  fi
  NGRINDER_STARTED=false
}

cleanup() {
  cleanup_app
  stop_ngrinder
  docker rm -f "$MYSQL_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  if [[ -d "$WORK_DIR/mvc" ]]; then
    git -C "$REPO_DIR" worktree remove --force "$WORK_DIR/mvc" >/dev/null 2>&1 || true
  fi
  if [[ -d "$WORK_DIR/webflux" ]]; then
    git -C "$REPO_DIR" worktree remove --force "$WORK_DIR/webflux" >/dev/null 2>&1 || true
  fi
  if [[ "$REMOTE_PREPARED" == "true" && -n "$LOAD_GENERATOR_SSH" ]]; then
    ssh "$LOAD_GENERATOR_SSH" "rm -rf '$REMOTE_LOADTEST_DIR'" >/dev/null 2>&1 || true
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

check_free_disk() {
  local available_kb
  available_kb="$(df -Pk "$WORK_DIR" | awk 'NR == 2 { print $4 }')"
  if [[ -z "$available_kb" || "$available_kb" -lt $((MIN_FREE_DISK_MB * 1024)) ]]; then
    echo "At least ${MIN_FREE_DISK_MB} MiB free disk is required." >&2
    exit 1
  fi
}

prepare_generator() {
  if [[ -z "$LOAD_GENERATOR_SSH" ]]; then
    return
  fi
  require_command ssh
  require_command rsync
  ssh "$LOAD_GENERATOR_SSH" "rm -rf '$REMOTE_LOADTEST_DIR'; mkdir -p '$REMOTE_LOADTEST_DIR'"
  rsync -az --delete \
    --exclude results --exclude __pycache__ \
    "$SCRIPT_DIR/" "$LOAD_GENERATOR_SSH:$REMOTE_LOADTEST_DIR/"
  ssh "$LOAD_GENERATOR_SSH" \
    "chmod +x '$REMOTE_LOADTEST_DIR/generator/'*.sh '$REMOTE_LOADTEST_DIR/ngrinder/stack.sh'"
  ssh "$LOAD_GENERATOR_SSH" "command -v python3 >/dev/null"
  if [[ "$TOOL" == "k6" || "$TOOL" == "all" ]]; then
    ssh "$LOAD_GENERATOR_SSH" "command -v k6 >/dev/null"
  fi
  if [[ "$TOOL" == "ngrinder" || "$TOOL" == "all" ]]; then
    ssh "$LOAD_GENERATOR_SSH" "command -v docker >/dev/null"
  fi
  REMOTE_PREPARED=true
}

capture_generator_machine() {
  local output="$RESULTS_DIR/generator-machine.json"
  local started_ns finished_ns
  started_ns="$(python3 -c 'import time; print(time.time_ns())')"
  if [[ -n "$LOAD_GENERATOR_SSH" ]]; then
    ssh "$LOAD_GENERATOR_SSH" \
      "cd '$REMOTE_LOADTEST_DIR' && \
      DOCKER_CONFIG='$REMOTE_LOADTEST_DIR/generator/docker-config' \
      python3 generator/machine_info.py" >"$output"
  else
    python3 "$SCRIPT_DIR/generator/machine_info.py" >"$output"
  fi
  finished_ns="$(python3 -c 'import time; print(time.time_ns())')"
  python3 - "$output" "$started_ns" "$finished_ns" "$MAX_CLOCK_SKEW_MS" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
started_ns, finished_ns = int(sys.argv[2]), int(sys.argv[3])
maximum_ms = float(sys.argv[4])
data = json.loads(path.read_text())
midpoint_ns = (started_ns + finished_ns) // 2
skew_ms = (int(data["capturedAtEpochNs"]) - midpoint_ns) / 1_000_000
data["clockSync"] = {
    "measuredSkewMs": round(skew_ms, 3),
    "roundTripMs": round((finished_ns - started_ns) / 1_000_000, 3),
    "maximumAllowedSkewMs": maximum_ms,
    "valid": abs(skew_ms) <= maximum_ms,
}
path.write_text(json.dumps(data, indent=2) + "\n")
if not data["clockSync"]["valid"]:
    raise SystemExit(
        f"Load generator clock skew {skew_ms:.1f} ms exceeds {maximum_ms:.1f} ms"
    )
PY
}

write_token_file() {
  local local_file="$WORK_DIR/access-token"
  umask 077
  printf '%s' "$ACCESS_TOKEN" >"$local_file"
  if [[ -n "$LOAD_GENERATOR_SSH" ]]; then
    ssh "$LOAD_GENERATOR_SSH" "umask 077; cat > '$REMOTE_LOADTEST_DIR/access-token'" <"$local_file"
  fi
}

start_ngrinder() {
  if [[ "$TOOL" == "k6" ]]; then
    return
  fi
  if [[ -n "$LOAD_GENERATOR_SSH" ]]; then
    ssh "$LOAD_GENERATOR_SSH" \
      "DOCKER_CONFIG='$REMOTE_LOADTEST_DIR/generator/docker-config' \
      NGRINDER_DATA_DIR='$REMOTE_LOADTEST_DIR/ngrinder-data' \
      '$REMOTE_LOADTEST_DIR/ngrinder/stack.sh' up"
  else
    NGRINDER_DATA_DIR="$NGRINDER_DATA_DIR" "$SCRIPT_DIR/ngrinder/stack.sh" up
  fi
  NGRINDER_STARTED=true
}

start_dependencies() {
  docker rm -f "$MYSQL_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$MYSQL_CONTAINER" \
    --cpus="$JVM_CPU_COUNT" --memory=1g \
    -p "$MYSQL_PORT:3306" \
    -e MYSQL_DATABASE=buddystudy \
    -e MYSQL_USER=buddystudy \
    -e MYSQL_PASSWORD=benchmark-password \
    -e MYSQL_ROOT_PASSWORD=benchmark-root-password \
    mysql:8.4 >/dev/null
  docker run -d --name "$REDIS_CONTAINER" \
    --cpus=1 --memory=256m \
    -p "$REDIS_PORT:6379" redis:7-alpine >/dev/null

  for _ in $(seq 1 60); do
    if docker exec "$MYSQL_CONTAINER" mysqladmin ping -h 127.0.0.1 -u buddystudy \
         -pbenchmark-password --silent >/dev/null 2>&1 && \
       docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q PONG; then
      return
    fi
    sleep 1
  done
  echo "Benchmark dependencies did not become ready." >&2
  exit 1
}

reset_dependencies() {
  docker exec "$MYSQL_CONTAINER" mysql -u root -pbenchmark-root-password \
    -e 'drop database if exists buddystudy; create database buddystudy character set utf8mb4 collate utf8mb4_0900_ai_ci; grant all privileges on buddystudy.* to '\''buddystudy'\''@'\''%'\'';' >/dev/null
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
  jar="$(find "$source_dir/backend/tutor/build/libs" -maxdepth 1 \
    -name 'tutor-*.jar' ! -name '*-plain.jar' -print -quit)"
  if [[ -z "$jar" || ! -s "$jar" ]]; then
    echo "Backend JAR was not produced for $source_dir" >&2
    return 1
  fi
  echo "$jar"
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
    DATABASE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/buddystudy?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
    R2DBC_DATABASE_URL="r2dbc:mysql://127.0.0.1:$MYSQL_PORT/buddystudy" \
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
    ADMIN_ANALYTICS_ENABLED=false \
    ADMIN_ANALYTICS_DATABASE_NAME= \
    BUDDYSTUDY_STREAMS_ENABLED=false \
    ENABLE_OPENAPI_DOCS=false \
    WEBFLUX_BLOCKING_CORE_SIZE="$BLOCKING_MAX_SIZE" \
    WEBFLUX_BLOCKING_MAX_SIZE="$BLOCKING_MAX_SIZE" \
    WEBFLUX_BLOCKING_QUEUE_CAPACITY="$BLOCKING_QUEUE_CAPACITY" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics \
    LOGGING_LEVEL_COM_BUDDYSTUDY_BACKEND_COMMON_ADAPTER_INBOUND_WEB="$BENCHMARK_LOGGING" \
    LOGGING_LEVEL_REACTOR_CORE_SCHEDULER_SCHEDULERS="$BENCHMARK_LOGGING" \
    "$JAVA_BIN" "${jvm_options[@]}" -jar "$jar" >"$log_file" 2>&1 &
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
  {
    printf "set @device_id = '%s';\n" "$DEVICE_ID"
    cat "$SCRIPT_DIR/fixtures/seed.sql"
  } | docker exec -i "$MYSQL_CONTAINER" mysql -u buddystudy \
    -pbenchmark-password buddystudy >/dev/null
  write_token_file
}

start_server_telemetry() {
  local output="$1"
  python3 "$SCRIPT_DIR/telemetry.py" \
    --pid "$APP_PID" \
    --base-url "http://127.0.0.1:$APP_PORT" \
    --output "$output" \
    --interval "$TELEMETRY_INTERVAL" \
    --mysql-container "$MYSQL_CONTAINER" \
    --redis-container "$REDIS_CONTAINER" &
  TELEMETRY_PID=$!
}

diagnostics_start() {
  local name="$1"
  local prefix="$2"
  local jfr_file="$3"
  if [[ "$ENABLE_NMT" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" VM.native_memory baseline >"$prefix-nmt-baseline.txt" 2>&1 || true
  fi
  if [[ "$ENABLE_JFR" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" JFR.start name="$name" settings=profile filename="$jfr_file" \
      >"$prefix-jfr-start.txt" 2>&1 || true
  fi
}

diagnostics_stop() {
  local name="$1"
  local prefix="$2"
  local jfr_file="$3"
  if [[ "$ENABLE_JFR" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" JFR.stop name="$name" >"$prefix-jfr-stop.txt" 2>&1 || true
    if [[ -s "$jfr_file" && -x "$JFR_BIN" ]]; then
      "$JFR_BIN" summary "$jfr_file" >"$prefix-jfr-summary.txt" 2>&1 || true
    fi
  fi
  if [[ "$ENABLE_NMT" == "true" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" VM.native_memory summary.diff scale=KB \
      >"$prefix-nmt-diff.txt" 2>&1 || true
  fi
  if [[ "$PROFILE" == "diagnostic" && -x "$JCMD_BIN" ]]; then
    "$JCMD_BIN" "$APP_PID" Thread.print -l >"$prefix-threads.txt" 2>&1 || true
    "$JCMD_BIN" "$APP_PID" GC.heap_info >"$prefix-heap.txt" 2>&1 || true
  fi
}

remote_run() {
  local command="$1"
  ssh "$LOAD_GENERATOR_SSH" "$command"
}

collect_remote_stage() {
  local remote_stage="$1"
  rsync -az "$LOAD_GENERATOR_SSH:$remote_stage/" "$RESULTS_DIR/"
  remote_run "rm -rf '$remote_stage'"
}

run_k6_stage() {
  local runtime="$1" round="$2" scenario="$3" target_rps="$4" measured="${5:-true}"
  local suffix="${scenario}-rps${target_rps}"
  local warmup=""
  [[ "$measured" == "true" ]] || warmup="-warmup"
  local base="${runtime}-round${round}-${suffix}${warmup}"
  local summary="$RESULTS_DIR/raw/$base.json"
  local timeseries="$RESULTS_DIR/timeseries/$base.json"
  local generator_telemetry="$RESULTS_DIR/generator-telemetry/$base.jsonl"
  local dashboard="$RESULTS_DIR/k6-dashboard/$base.html"
  local log="$RESULTS_DIR/logs/k6-$base.log"
  local duration="$DURATION"
  local validation=true
  local strict_validation=false
  if [[ "$measured" != "true" ]]; then
    duration="$WARMUP_DURATION"
    strict_validation=true
  fi

  if [[ -n "$LOAD_GENERATOR_SSH" ]]; then
    local remote_stage="$REMOTE_LOADTEST_DIR/output-$base"
    remote_run "mkdir -p '$remote_stage/raw' '$remote_stage/timeseries' '$remote_stage/generator-telemetry' '$remote_stage/k6-dashboard' '$remote_stage/logs'; \
      BASE_URL='$TARGET_BASE_URL' ACCESS_TOKEN_FILE='$REMOTE_LOADTEST_DIR/access-token' \
      SCENARIO='$scenario' TARGET_RPS='$target_rps' DURATION='$duration' \
      REQUEST_TIMEOUT='$REQUEST_TIMEOUT' STUDIES_LIMIT='$STUDIES_LIMIT' \
      PRE_ALLOCATED_VUS='$PRE_ALLOCATED_VUS' MAX_VUS='$MAX_VUS' VALIDATE_BODY='$validation' \
      STRICT_VALIDATION='$strict_validation' \
      GENERATOR_TELEMETRY_INTERVAL='$GENERATOR_TELEMETRY_INTERVAL' \
      SUMMARY_PATH='$remote_stage/raw/$base.json' \
      TIMESERIES_PATH='$remote_stage/timeseries/$base.json' \
      TELEMETRY_PATH='$remote_stage/generator-telemetry/$base.jsonl' \
      DASHBOARD_PATH='$remote_stage/k6-dashboard/$base.html' \
      LOG_PATH='$remote_stage/logs/k6-$base.log' \
      '$REMOTE_LOADTEST_DIR/generator/run-k6.sh'"
    collect_remote_stage "$remote_stage"
  else
    BASE_URL="$TARGET_BASE_URL" ACCESS_TOKEN_FILE="$WORK_DIR/access-token" \
      SCENARIO="$scenario" TARGET_RPS="$target_rps" DURATION="$duration" \
      REQUEST_TIMEOUT="$REQUEST_TIMEOUT" STUDIES_LIMIT="$STUDIES_LIMIT" \
      PRE_ALLOCATED_VUS="$PRE_ALLOCATED_VUS" MAX_VUS="$MAX_VUS" VALIDATE_BODY="$validation" \
      STRICT_VALIDATION="$strict_validation" \
      GENERATOR_TELEMETRY_INTERVAL="$GENERATOR_TELEMETRY_INTERVAL" \
      SUMMARY_PATH="$summary" TIMESERIES_PATH="$timeseries" \
      TELEMETRY_PATH="$generator_telemetry" DASHBOARD_PATH="$dashboard" LOG_PATH="$log" \
      "$SCRIPT_DIR/generator/run-k6.sh"
  fi
}

run_ngrinder_stage() {
  local runtime="$1" round="$2" scenario="$3" vusers="$4"
  local suffix="${scenario}-vu${vusers}"
  local base="${runtime}-round${round}-${suffix}"
  local summary="$RESULTS_DIR/raw/ngrinder-$base.json"
  local timeseries="$RESULTS_DIR/timeseries/ngrinder-$base.json"
  local generator_telemetry="$RESULTS_DIR/generator-telemetry/ngrinder-$base.jsonl"
  local log="$RESULTS_DIR/logs/ngrinder-$base.log"

  if [[ -n "$LOAD_GENERATOR_SSH" ]]; then
    local remote_stage="$REMOTE_LOADTEST_DIR/output-ngrinder-$base"
    remote_run "mkdir -p '$remote_stage/raw' '$remote_stage/timeseries' '$remote_stage/generator-telemetry' '$remote_stage/logs'; \
      BASE_URL='$NGRINDER_TARGET_BASE_URL' ACCESS_TOKEN_FILE='$REMOTE_LOADTEST_DIR/access-token' \
      SCENARIO='$scenario' VUS='$vusers' REQUEST_TIMEOUT_MS='$REQUEST_TIMEOUT_MS' \
      STUDIES_LIMIT='$STUDIES_LIMIT' NGRINDER_RAMP_SECONDS='$NGRINDER_RAMP_SECONDS' \
      NGRINDER_HOLD_SECONDS='$NGRINDER_HOLD_SECONDS' \
      NGRINDER_MAX_PROCESSES='$NGRINDER_MAX_PROCESSES' \
      NGRINDER_MAX_THREADS_PER_PROCESS='$NGRINDER_MAX_THREADS_PER_PROCESS' \
      GENERATOR_TELEMETRY_INTERVAL='$GENERATOR_TELEMETRY_INTERVAL' \
      SUMMARY_PATH='$remote_stage/raw/ngrinder-$base.json' \
      TIMESERIES_PATH='$remote_stage/timeseries/ngrinder-$base.json' \
      TELEMETRY_PATH='$remote_stage/generator-telemetry/ngrinder-$base.jsonl' \
      LOG_PATH='$remote_stage/logs/ngrinder-$base.log' \
      '$REMOTE_LOADTEST_DIR/generator/run-ngrinder.sh'"
    collect_remote_stage "$remote_stage"
  else
    BASE_URL="$NGRINDER_TARGET_BASE_URL" ACCESS_TOKEN_FILE="$WORK_DIR/access-token" \
      SCENARIO="$scenario" VUS="$vusers" REQUEST_TIMEOUT_MS="$REQUEST_TIMEOUT_MS" \
      STUDIES_LIMIT="$STUDIES_LIMIT" NGRINDER_RAMP_SECONDS="$NGRINDER_RAMP_SECONDS" \
      NGRINDER_HOLD_SECONDS="$NGRINDER_HOLD_SECONDS" \
      NGRINDER_MAX_PROCESSES="$NGRINDER_MAX_PROCESSES" \
      NGRINDER_MAX_THREADS_PER_PROCESS="$NGRINDER_MAX_THREADS_PER_PROCESS" \
      GENERATOR_TELEMETRY_INTERVAL="$GENERATOR_TELEMETRY_INTERVAL" \
      SUMMARY_PATH="$summary" TIMESERIES_PATH="$timeseries" \
      TELEMETRY_PATH="$generator_telemetry" LOG_PATH="$log" \
      "$SCRIPT_DIR/generator/run-ngrinder.sh"
  fi
}

run_measured_stage() {
  local tool="$1" runtime="$2" round="$3" scenario="$4" load="$5"
  local kind="rps"
  [[ "$tool" == "k6" ]] || kind="vu"
  local stage="${scenario}-${kind}${load}"
  local prefix="$RESULTS_DIR/diagnostics/${tool}-${runtime}-round${round}-${stage}"
  local telemetry="$RESULTS_DIR/telemetry/${tool}-${runtime}-round${round}-${stage}.jsonl"
  local recording="${tool}_${runtime}_round${round}_${scenario//-/_}_${kind}${load}"
  local jfr="$RESULTS_DIR/jfr/${tool}-${runtime}-round${round}-${stage}.jfr"
  diagnostics_start "$recording" "$prefix" "$jfr"
  start_server_telemetry "$telemetry"
  local status=0
  if [[ "$tool" == "k6" ]]; then
    run_k6_stage "$runtime" "$round" "$scenario" "$load" true || status=$?
  else
    run_ngrinder_stage "$runtime" "$round" "$scenario" "$load" || status=$?
  fi
  stop_telemetry
  diagnostics_stop "$recording" "$prefix" "$jfr"
  python3 "$SCRIPT_DIR/recovery_probe.py" \
    --url "http://127.0.0.1:$APP_PORT/health" \
    --duration-seconds "$STAGE_COOLDOWN_SECONDS" \
    --output "$RESULTS_DIR/recovery/${tool}-${runtime}-round${round}-${stage}.json"
  return "$status"
}

fine_sweep_rates() {
  local runtime="$1" round="$2" scenario="$3"
  python3 "$SCRIPT_DIR/find_saturation.py" \
    --results "$RESULTS_DIR" --runtime "$runtime" --round "$round" \
    --scenario "$scenario" --tested "$TARGET_RPS_LIST"
}

run_runtime() {
  local runtime="$1" round="$2" jar="$3"
  echo "[$runtime round $round] reset and seed disposable dependencies"
  reset_dependencies
  start_app "$runtime" "$round" "$jar"
  seed_data

  local stage_index=0
  local scenario load
  for scenario in "${SCENARIOS_ARRAY[@]}"; do
    if [[ "$TOOL" == "k6" || "$TOOL" == "all" ]]; then
      echo "[$runtime round $round] validating $scenario"
      run_k6_stage "$runtime" "$round" "$scenario" "${TARGET_RATES[0]}" false
      for load in "${TARGET_RATES[@]}"; do
        if [[ "$RESTART_APP_PER_STAGE" == "true" && "$stage_index" -gt 0 ]]; then
          start_app "$runtime" "$round" "$jar"
        fi
        echo "[$runtime round $round] k6 $scenario at $load RPS"
        run_measured_stage k6 "$runtime" "$round" "$scenario" "$load"
        stage_index=$((stage_index + 1))
      done
      if [[ "$AUTO_FINE_SWEEP" == "true" ]]; then
        local fine_rates
        fine_rates="$(fine_sweep_rates "$runtime" "$round" "$scenario")"
        for load in $fine_rates; do
          echo "[$runtime round $round] k6 fine sweep $scenario at $load RPS"
          run_measured_stage k6 "$runtime" "$round" "$scenario" "$load"
        done
      fi
    fi

    if [[ "$TOOL" == "ngrinder" || "$TOOL" == "all" ]]; then
      for load in "${NGRINDER_VUS[@]}"; do
        echo "[$runtime round $round] nGrinder $scenario at $load VUser"
        run_measured_stage ngrinder "$runtime" "$round" "$scenario" "$load"
      done
    fi
  done

  ps -o rss= -p "$APP_PID" | awk '{ print $1 }' \
    >"$RESULTS_DIR/raw/${runtime}-round${round}-rss-kb.txt"
  cleanup_app
  sleep 2
}

write_metadata() {
  python3 "$SCRIPT_DIR/write_metadata.py" \
    --output "$RESULTS_DIR/metadata.json" \
    --profile "$PROFILE" --tool "$TOOL" \
    --mvc-ref "$MVC_COMMIT" --webflux-ref "$WEBFLUX_COMMIT" \
    --target-host "$TARGET_BASE_URL" --load-generator "${LOAD_GENERATOR_SSH:-local}" \
    --rounds "$ROUNDS" --target-rps "$TARGET_RPS_LIST" --vusers "$NGRINDER_VUS_LIST" \
    --max-concurrent-users "$MAX_CONCURRENT_USERS" \
    --ngrinder-max-processes "$NGRINDER_MAX_PROCESSES" \
    --ngrinder-max-threads-per-process "$NGRINDER_MAX_THREADS_PER_PROCESS" \
    --scenarios "$SCENARIO_LIST" --duration "$DURATION" \
    --heap "$JVM_HEAP" --cpu "$JVM_CPU_COUNT" --db-pool "$DB_POOL_MAX" \
    --jfr "$ENABLE_JFR" --nmt "$ENABLE_NMT" \
    --generator-machine-file "$RESULTS_DIR/generator-machine.json" \
    --java-bin "$JAVA_BIN" \
    --generator-network-capacity-mbps "$GENERATOR_NETWORK_CAPACITY_MBPS"
}

write_reports() {
  python3 "$SCRIPT_DIR/normalize_results.py" "$RESULTS_DIR"
  python3 "$SCRIPT_DIR/report_results.py" "$RESULTS_DIR"
  python3 "$SCRIPT_DIR/render_comparison_dashboard.py" "$RESULTS_DIR"
}

for command in docker git curl jq python3; do
  require_command "$command"
done
if [[ "$TOOL" == "k6" || "$TOOL" == "all" ]] && [[ -z "$LOAD_GENERATOR_SSH" ]]; then
  require_command k6
fi
if [[ ! -x "$JAVA_BIN" ]]; then
  echo "Benchmark Java executable was not found: $JAVA_BIN" >&2
  exit 1
fi

validate_inputs
check_free_disk
mkdir -p "$RESULTS_DIR"/{raw,logs,telemetry,generator-telemetry,timeseries,k6-dashboard,jfr,diagnostics,recovery}
mkdir -p "$LOADTEST_DOCKER_CONFIG"
cp "$SCRIPT_DIR/generator/docker-config/config.json" "$LOADTEST_DOCKER_CONFIG/config.json"
export DOCKER_CONFIG="$LOADTEST_DOCKER_CONFIG"
prepare_generator
capture_generator_machine
start_ngrinder
start_dependencies

echo "Preparing MVC $MVC_REF"
MVC_DIR="$(prepare_worktree mvc "$MVC_REF")"
MVC_COMMIT="$(git -C "$MVC_DIR" rev-parse HEAD)"
MVC_JAR="$(build_jar "$MVC_DIR")"

echo "Preparing WebFlux $WEBFLUX_REF"
WEBFLUX_DIR="$(prepare_worktree webflux "$WEBFLUX_REF")"
WEBFLUX_COMMIT="$(git -C "$WEBFLUX_DIR" rev-parse HEAD)"
WEBFLUX_JAR="$(build_jar "$WEBFLUX_DIR")"
write_metadata

for round in $(seq 1 "$ROUNDS"); do
  if (( round % 2 == 1 )); then
    run_runtime mvc "$round" "$MVC_JAR"
    run_runtime webflux "$round" "$WEBFLUX_JAR"
  else
    run_runtime webflux "$round" "$WEBFLUX_JAR"
    run_runtime mvc "$round" "$MVC_JAR"
  fi
done

write_reports
echo "Benchmark report: $RESULTS_DIR/REPORT.md"
echo "Benchmark dashboard: $RESULTS_DIR/DASHBOARD.html"
