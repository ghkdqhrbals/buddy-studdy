# MVC vs WebFlux Load Test

This benchmark compares the last MVC/JDBC runtime with the current WebFlux/R2DBC runtime using the same PostgreSQL fixture, Redis instance, JVM heap, visible CPU count, and database connection-pool size.

## Compared Workloads

| Scenario | Endpoint | What it measures |
| --- | --- | --- |
| `health` | `GET /health` | HTTP server, routing, and JSON serialization without persistence |
| `public-questions` | `GET /api/v1/public/questions?limit=20...` | Database reads, multiple repository lookups, and a 20-row response |
| `studies` | `GET /api/v1/studies?limit=<STUDIES_LIMIT>...` | JWT verification, DB-backed session validation, database pagination, and a configurable response size (default 100) |

The fixture contains one registered user, 100 studies, and 500 graded public questions. Testing an empty database is intentionally avoided because it hides query, mapping, and serialization costs.

## Requirements

- Docker
- Java compatible with the project
- k6
- `curl`, `jq`, Python 3, and Git
- At least 4 GiB of free disk space for detached worktrees, two JAR builds, JFR recordings, and raw results

Install k6 on macOS with `brew install k6`.

On macOS the runner selects JDK 25 through `/usr/libexec/java_home`. Elsewhere, or when using another compatible JDK, set `BENCHMARK_JAVA_BIN=/path/to/java` explicitly. The JAR must not be launched with an older Java runtime than the project's Kotlin/JVM target.

## Run

From the repository root:

```bash
ROUNDS=3 DURATION=30s backend/loadtest/run-comparison.sh
```

The default comparison is:

- MVC: `eca7e320`, the commit immediately before the WebFlux migration
- WebFlux: `HEAD`
- JVM: 512 MiB fixed heap and four visible processors
- Database pool: 10 connections (Hikari for MVC, R2DBC Pool for WebFlux)
- MVC Tomcat workers: 16; WebFlux uses Reactor Netty event loops based on the four visible processors
- API exchange log emission: disabled. The current filter still captures bodies and formats messages before SLF4J drops them; account for that cost until the filter is made log-level aware.
- Scheduler, stream consumers, and admin analytics jobs: disabled for API isolation
- Constant arrival-rate stages: 1,000, 1,500, 2,000, 2,500, and 3,000 RPS per API
- Latency percentiles: p50, p90, p95, and p99, reported with HTTP RPS, successful RPS, failures, and dropped iterations

Raw k6 summaries, one-second request time series, application logs, resource telemetry, JFR recordings, and JVM diagnostics are written under `backend/loadtest/results/<UTC timestamp>/`. This directory is ignored by Git.

Each run also produces a self-contained `DASHBOARD.html` with nGrinder-style MVC/WebFlux overlays for successful TPS, p90/p95 response time, failures, dropped iterations, JVM CPU, RSS, heap, and thread counts. It can be opened directly in a browser without a server. Use `?scenario=public-questions&rate=3000` to deep-link a specific endpoint and target rate.

Each measured API interval collects the following at a two-second default cadence:

- JVM process CPU, normalized process/host CPU, RSS, virtual memory, open files, and OS/JVM thread counts.
- Heap, non-heap, and direct-buffer memory; allocation rate; GC count and pause time.
- Hikari active/pending connections for MVC and R2DBC Pool acquired/pending connections for WebFlux.
- MVC Tomcat busy workers or WebFlux active HTTP requests and Reactor Netty pending tasks.
- PostgreSQL active/idle/waiting connections, transaction and buffer-cache counters, plus container CPU and memory.
- Redis clients, memory, operations, cache/eviction counters, plus container CPU and memory.
- Per-scenario JFR `profile` recordings, JVM thread dumps, heap summaries, and Native Memory Tracking diffs.

The generated `REPORT.md` aggregates each scenario within each round and then reports the median across rounds. Raw JSONL is retained so spikes and time correlation are not lost behind the summary.

The first checked-in baseline and its interpretation are in [`docs/performance/MVC_VS_WEBFLUX_2026-07-21.md`](../../docs/performance/MVC_VS_WEBFLUX_2026-07-21.md).

Useful variations:

```bash
# Production-shaped logging cost
BENCHMARK_LOGGING=INFO ROUNDS=3 DURATION=30s backend/loadtest/run-comparison.sh

# Finer sweep around an observed saturation knee
TARGET_RPS_LIST=1500,1750,2000,2250,2500 ROUNDS=5 DURATION=60s \
  backend/loadtest/run-comparison.sh

# Isolate one endpoint and restart the application before every measured rate
SCENARIO_LIST=studies TARGET_RPS_LIST=100,200,300,400,500,600 \
  RESTART_APP_PER_STAGE=true backend/loadtest/run-comparison.sh

# Control response-row materialization cost
SCENARIO_LIST=studies TARGET_RPS_LIST=400 STUDIES_LIMIT=1 \
  backend/loadtest/run-comparison.sh

# Explicit refs
MVC_REF=eca7e320 WEBFLUX_REF=e264c103 backend/loadtest/run-comparison.sh

# Reduce profiler perturbation when measuring only request throughput
ENABLE_JFR=false ENABLE_NMT=false backend/loadtest/run-comparison.sh

# Change resource sampling cadence
TELEMETRY_INTERVAL=1 ROUNDS=3 DURATION=60s backend/loadtest/run-comparison.sh

# Increase k6 VU capacity for endpoints that remain slow under the 3,000 RPS stage
PRE_ALLOCATED_VUS=1000 MAX_VUS=4000 backend/loadtest/run-comparison.sh

# Override the disk preflight only when build artifacts are known to fit
MIN_FREE_DISK_MB=2048 backend/loadtest/run-comparison.sh
```

## Reading Results

- Use at least three rounds and compare medians. Five rounds are preferable on a developer laptop.
- Differences below 5% are usually noise until reproduced on the deployment host.
- Run k6 from a different machine before treating the absolute RPS as production capacity. A same-host run is useful for regression comparison but introduces CPU contention between k6, the JVM, and Docker Desktop.
- Compare achieved RPS, dropped iterations, error rate, and then p90/p95/p99. At saturation, completed-request latency alone is selection-biased.
- `health` is not evidence that DB-backed APIs improved.
- MVC uses JPA/JDBC/Hikari; current WebFlux uses coroutine repositories over R2DBC Pool. PostgreSQL capacity still bounds useful DB concurrency in both cases.
- A WebFlux result with similar peak RPS can still be preferable if it maintains lower tail latency with slow clients and rejects overload predictably.
- Compare CPU at the same RPS as well as maximum RPS. A framework that reaches a similar RPS with more CPU has less production headroom.
- macOS `ps` process CPU can exceed 100% because it counts CPU cores. Actuator process CPU is normalized to the JVM-visible processor count and host CPU includes k6 and Docker Desktop contention.
- RSS is not heap. Use heap/non-heap/direct metrics and NMT together to explain a change in RSS.
- A high live-thread count is not automatically bad. Check runnable/blocked states in the matching thread dump and correlate pool queues with p95/p99 latency.
- A nonzero Hikari pending count or PostgreSQL waiting count indicates pool/database pressure; increasing HTTP worker threads cannot resolve it by itself.
- JFR `profile` and NMT add small overhead. Keep both settings identical between runtimes and disable both only for a separate throughput-only control run.

Inspect a recording with JDK Mission Control or the JDK CLI:

```bash
jfr summary backend/loadtest/results/<run>/jfr/<recording>.jfr
jfr view hot-methods backend/loadtest/results/<run>/jfr/<recording>.jfr
jfr view allocation-by-site backend/loadtest/results/<run>/jfr/<recording>.jfr
```

Do not run the write endpoints against production. The harness uses disposable local containers and deletes them on exit.
