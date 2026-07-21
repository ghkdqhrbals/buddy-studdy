# MVC vs WebFlux Baseline - 2026-07-21

## Result

The current hybrid WebFlux runtime is not faster than MVC for BuddyStudy's existing blocking JPA workload. Its value must be justified by high-connection or slow-client behavior, not assumed from the WebFlux label.

## Arrival-Rate Saturation Sweep

A second benchmark used constant arrival rates from 1,000 through 3,000 RPS. Each stage ran for 15 seconds in three alternating-order rounds. The table below reports the median. `Successful RPS` excludes non-2xx responses; HTTP response throughput alone is misleading when WebFlux rejects work quickly.

| Endpoint / target | Runtime | Successful RPS | Target met | p95 ms | Failed | Dropped |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Health / 3,000 | MVC | 2,999.9 | 100.0% | 0.21 | 0.0% | 0 |
| Health / 3,000 | WebFlux | 2,999.8 | 100.0% | 0.24 | 0.0% | 0 |
| Public questions / 1,000 | MVC | 712.6 | 71.3% | 1,440.40 | 0.0% | 3,388 |
| Public questions / 1,000 | WebFlux | 586.0 | 58.6% | 164.72 | 41.0% | 0 |
| Public questions / 3,000 | MVC | 734.5 | 24.5% | 4,164.41 | 0.0% | 30,975 |
| Public questions / 3,000 | WebFlux | 597.0 | 19.9% | 134.93 | 80.0% | 0 |
| Studies / 2,000 | MVC | 1,999.6 | 100.0% | 10.69 | 0.0% | 0 |
| Studies / 2,000 | WebFlux | 1,943.0 | 97.1% | 43.34 | 2.8% | 0 |
| Studies / 2,500 | MVC | 2,496.9 | 99.9% | 100.99 | 0.0% | 0 |
| Studies / 2,500 | WebFlux | 1,570.7 | 62.8% | 50.86 | 37.0% | 0 |
| Studies / 3,000 | MVC | 2,376.3 | 79.2% | 1,321.51 | 0.0% | 6,367 |
| Studies / 3,000 | WebFlux | 1,361.9 | 45.4% | 54.27 | 54.5% | 0 |

The lower WebFlux p95 values in rejecting stages are not a latency win: failed requests return quickly after the 16-worker, 64-entry blocking executor saturates. For example, public questions at 3,000 target RPS produced 2,977.7 HTTP responses per second but only 597.0 successful responses per second. MVC queued work at the load generator instead, producing no HTTP failures but large latency and dropped iterations. Neither runtime sustains 1,000 successful public-question requests per second under this configuration.

For authenticated studies, MVC is the practical winner with the current blocking stack. It sustains 2,500 successful RPS with no failures while WebFlux starts rejecting at 2,000 RPS and returns only 1,570.7 successful RPS at 2,500. On the no-DB health endpoint both reach 3,000 RPS, but WebFlux p95 is 16.1% slower and uses more process CPU and threads at that stage (34.5% vs 23.1% median CPU, 80 vs 58 peak OS threads). Median RSS is effectively equal: 893.2 MiB MVC and 888.7 MiB WebFlux.

The final run explicitly disabled the request logging package and verified that the application logs contained zero `api_response` or `api_exchange` events. The self-contained HTML report also retains one-second successful TPS, p90/p95, failure, dropped-work, CPU, RSS, heap, and thread time series.

| Endpoint | Runtime | RPS | p50 ms | p95 ms | p99 ms | Failed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `GET /health` | MVC | 40,354.1 | 1.17 | 1.78 | 2.77 | 0% |
| `GET /health` | WebFlux | 30,160.7 | 1.40 | 3.33 | 5.19 | 0% |
| Public questions, 20 rows | MVC | 784.4 | 62.41 | 74.55 | 89.48 | 0% |
| Public questions, 20 rows | WebFlux | 768.0 | 63.48 | 76.18 | 88.43 | 0% |
| Authenticated studies, 100 rows | MVC | 2,588.1 | 18.89 | 22.68 | 27.94 | 0% |
| Authenticated studies, 100 rows | WebFlux | 2,047.8 | 23.81 | 29.01 | 35.22 | 0% |

Median process RSS was approximately 934 MiB for MVC and 917 MiB for WebFlux. RSS includes JVM native memory and committed heap, so it is not an allocation-rate measurement.

This first baseline predates the comprehensive telemetry collector. It must not be used to make CPU, thread-pool, allocation, or GC claims. The load-test harness now records those signals per API interval, but a new profiled run must be compared separately because JFR, NMT, and metric polling add equal but nonzero overhead to both runtimes.

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
ROUNDS=3 DURATION=30s backend/loadtest/run-comparison.sh
```

The current harness uses constant arrival-rate stages from 1,000 through 3,000 RPS and reports p90/p95 together with HTTP RPS, successful RPS, failure rate, and dropped iterations. It also generates an nGrinder-style `DASHBOARD.html`. This is a different workload model from the historical 50-VU baseline above, so compare results only within the same harness configuration.

Before production decisions, rerun a finer sweep around the observed saturation point from another host:

```bash
TARGET_RPS_LIST=1500,1750,2000,2250,2500 ROUNDS=5 DURATION=60s \
  backend/loadtest/run-comparison.sh
```

For each arrival-rate stage, retain and compare the generated resource tables, JFR recordings, NMT diffs, and thread dumps. Throughput and latency alone are insufficient when the runtimes use different threading models.
