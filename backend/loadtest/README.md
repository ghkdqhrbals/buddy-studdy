# MVC vs WebFlux Load Test

This benchmark compares the last MVC runtime with the current WebFlux runtime using the same application code, PostgreSQL fixture, Redis instance, JVM heap, visible CPU count, and Hikari pool size.

## Compared Workloads

| Scenario | Endpoint | What it measures |
| --- | --- | --- |
| `health` | `GET /health` | HTTP server, routing, and JSON serialization without persistence |
| `public-questions` | `GET /api/v1/public/questions?limit=20...` | JPA reads, multiple repository lookups, and a 20-row response |
| `studies` | `GET /api/v1/studies?limit=100...` | JWT verification, DB-backed session validation, JPA pagination, and a 100-row response |

The fixture contains one registered user, 100 studies, and 500 graded public questions. Testing an empty database is intentionally avoided because it hides query, mapping, and serialization costs.

## Requirements

- Docker
- Java compatible with the project
- k6
- `curl`, `jq`, Python 3, and Git

Install k6 on macOS with `brew install k6`.

On macOS the runner selects JDK 25 through `/usr/libexec/java_home`. Elsewhere, or when using another compatible JDK, set `BENCHMARK_JAVA_BIN=/path/to/java` explicitly. The JAR must not be launched with an older Java runtime than the project's Kotlin/JVM target.

## Run

From the repository root:

```bash
ROUNDS=3 VUS=50 DURATION=30s backend/loadtest/run-comparison.sh
```

The default comparison is:

- MVC: `eca7e320`, the commit immediately before the WebFlux migration
- WebFlux: `HEAD`
- JVM: 512 MiB fixed heap and four visible processors
- Hikari: 10 connections
- Blocking request concurrency: 16 for both MVC/Tomcat and WebFlux
- API exchange logging: disabled for framework isolation

Raw k6 summaries, application logs, RSS samples, and a median comparison table are written under `backend/loadtest/results/<UTC timestamp>/`. This directory is ignored by Git.

The first checked-in baseline and its interpretation are in [`docs/performance/MVC_VS_WEBFLUX_2026-07-21.md`](../../docs/performance/MVC_VS_WEBFLUX_2026-07-21.md).

Useful variations:

```bash
# Production-shaped logging cost
BENCHMARK_LOGGING=INFO ROUNDS=3 VUS=50 DURATION=30s backend/loadtest/run-comparison.sh

# Concurrency sweep
for vus in 25 50 100 200; do
  VUS=$vus ROUNDS=5 DURATION=60s backend/loadtest/run-comparison.sh
done

# Explicit refs
MVC_REF=eca7e320 WEBFLUX_REF=e264c103 backend/loadtest/run-comparison.sh
```

## Reading Results

- Use at least three rounds and compare medians. Five rounds are preferable on a developer laptop.
- Differences below 5% are usually noise until reproduced on the deployment host.
- Run k6 from a different machine before treating the absolute RPS as production capacity. A same-host run is useful for regression comparison but introduces CPU contention between k6, the JVM, and Docker Desktop.
- Compare error rate and p95/p99 before average latency.
- `health` is not evidence that DB-backed APIs improved.
- Because persistence remains JPA/JDBC, Hikari and PostgreSQL still determine the useful DB concurrency.
- A WebFlux result with similar peak RPS can still be preferable if it maintains lower tail latency with slow clients and rejects overload predictably.

Do not run the write endpoints against production. The harness uses disposable local containers and deletes them on exit.
