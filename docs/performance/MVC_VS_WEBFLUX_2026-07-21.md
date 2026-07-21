# MVC vs WebFlux Baseline - 2026-07-21

## Result

The current hybrid WebFlux runtime is not faster than MVC for BuddyStudy's existing blocking JPA workload. Its value must be justified by high-connection or slow-client behavior, not assumed from the WebFlux label.

| Endpoint | Runtime | RPS | p50 ms | p95 ms | p99 ms | Failed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `GET /health` | MVC | 40,354.1 | 1.17 | 1.78 | 2.77 | 0% |
| `GET /health` | WebFlux | 30,160.7 | 1.40 | 3.33 | 5.19 | 0% |
| Public questions, 20 rows | MVC | 784.4 | 62.41 | 74.55 | 89.48 | 0% |
| Public questions, 20 rows | WebFlux | 768.0 | 63.48 | 76.18 | 88.43 | 0% |
| Authenticated studies, 100 rows | MVC | 2,588.1 | 18.89 | 22.68 | 27.94 | 0% |
| Authenticated studies, 100 rows | WebFlux | 2,047.8 | 23.81 | 29.01 | 35.22 | 0% |

Median process RSS was approximately 934 MiB for MVC and 917 MiB for WebFlux. RSS includes JVM native memory and committed heap, so it is not an allocation-rate measurement.

Relative WebFlux result:

- Health: 25.3% lower throughput.
- Public questions: 2.1% lower throughput; the difference is within normal same-host benchmark noise.
- Authenticated studies: 20.9% lower throughput and 27.9% worse p95 latency.

## Compared Versions

- MVC: `eca7e320`, immediately before the WebFlux migration.
- WebFlux: `e264c103`, the initial WebFlux migration.
- Both versions use the same application behavior and database migrations for these read APIs.

## Test Conditions

- MacBook Pro 2021, Apple M1 Max, 64 GB RAM, macOS 15.6.
- Oracle GraalVM JDK 25.0.2.
- k6 2.1.0 running on the same host as the backend.
- PostgreSQL 16 and Redis 7 running in Docker Desktop.
- Three rounds with alternating runtime order; table reports the median.
- 50 constant virtual users, 10-second warm-up, then 30-second measurement per endpoint.
- Fixed JVM heap: `-Xms512m -Xmx512m`.
- JVM-visible processors: 4.
- Hikari maximum pool: 10.
- Blocking request concurrency: 16 for both MVC/Tomcat and WebFlux.
- API exchange logging disabled to isolate runtime and application execution cost.
- Identical fixture per runtime: one user, 100 studies, 500 graded public questions.

## Practical Interpretation

`GET /health` is useful for detecting HTTP-runtime overhead but is not representative of BuddyStudy capacity. The load generator can also become a same-host CPU competitor at tens of thousands of requests per second.

The public-question API is database and mapping heavy. Its MVC and WebFlux values are effectively equivalent, showing that PostgreSQL/JPA work dominates the framework difference.

The authenticated studies API performs JWT validation, DB-backed device/session checks, a second blocking controller dispatch, JPA pagination, and JSON serialization. The WebFlux version pays Reactor scheduling and context-switch costs while retaining blocking persistence. This explains the repeatable regression.

This result does not prove that WebFlux has no benefit. It does show that peak RPS and normal local latency are not current benefits. A valid WebFlux decision requires additional tests for:

1. Thousands of idle or slow client connections.
2. Slow response consumers and streaming responses.
3. Blocking-executor saturation and `503 SERVER_BUSY` behavior.
4. Event-loop blocked-thread detection.
5. Production-host CPU, GC, Hikari, and PostgreSQL metrics with a separate load-generator host.

If those tests do not show a product-relevant advantage, MVC is the simpler and currently faster runtime for this blocking application. Moving only controller return types to `Mono` will not fix the authenticated API regression. A genuine non-blocking throughput redesign would require measuring and potentially replacing JPA/JDBC boundaries, not cosmetic reactive wrappers.

## Reproduction

Use the harness in [`backend/loadtest/README.md`](../../backend/loadtest/README.md):

```bash
ROUNDS=3 VUS=50 DURATION=30s backend/loadtest/run-comparison.sh
```

Before production decisions, run a concurrency sweep from another host:

```bash
for vus in 25 50 100 200; do
  VUS=$vus ROUNDS=5 DURATION=60s backend/loadtest/run-comparison.sh
done
```
