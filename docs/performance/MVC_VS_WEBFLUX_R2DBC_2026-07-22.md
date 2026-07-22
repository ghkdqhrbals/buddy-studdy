# MVC/JDBC vs WebFlux/R2DBC Benchmark - 2026-07-22

## Conclusion

The current WebFlux/R2DBC implementation is not ready to replace the MVC/JDBC baseline for BuddyStudy's database-backed APIs.

Both runtimes sustain the 3,000 RPS health check. This rules out Reactor Netty itself as the primary bottleneck. Under database load, however, WebFlux admits roughly 3,000 concurrent requests into a 10-connection R2DBC pool. The pool reaches 10 acquired connections and about 2,990 pending acquisitions, requests hit the 5-second client timeout, and successful throughput falls below MVC at every measured rate.

This result describes the current implementations. It does not prove that WebFlux or R2DBC is inherently slower. It shows that the current reactive request path lacks bounded admission/backpressure and performs too many database operations per request.

## Method

- MVC ref: `eca7e3204177f44474c6eab3ad77340a7b0543f9`
- WebFlux/R2DBC ref: `1b00033adf48a2c08524dfc30a7c5ad7e9efe865`
- Constant arrival rate: 1,000, 1,500, 2,000, 2,500, and 3,000 RPS
- Three alternating-order rounds; tables report the median
- Five-second warm-up and 15-second measured interval per stage
- Request timeout: five seconds
- JVM: `-Xms512m -Xmx512m -XX:ActiveProcessorCount=4`
- Database pool: 10 connections for both Hikari and R2DBC Pool
- MVC Tomcat workers: 16
- Fixture: one user, 100 studies, and 500 graded public questions
- API exchange logging and background analytics disabled
- k6 and the application ran on the same MacBook Pro; PostgreSQL and Redis ran in Docker

## Throughput And Latency

`Successful RPS` excludes failed responses. `Target met` is successful RPS divided by the requested arrival rate. Dropped iterations are work k6 could not start because all configured virtual users were occupied.

### Health

| Target | Runtime | Successful RPS | Target met | p90 | p95 | Failed | Dropped |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | MVC | 999.9 | 100.0% | 0.41 ms | 0.51 ms | 0.0% | 0 |
| 1,000 | WebFlux | 999.9 | 100.0% | 0.52 ms | 0.62 ms | 0.0% | 0 |
| 1,500 | MVC | 1,499.9 | 100.0% | 0.32 ms | 0.38 ms | 0.0% | 0 |
| 1,500 | WebFlux | 1,499.9 | 100.0% | 0.34 ms | 0.38 ms | 0.0% | 0 |
| 2,000 | MVC | 1,999.8 | 100.0% | 0.25 ms | 0.30 ms | 0.0% | 0 |
| 2,000 | WebFlux | 1,999.8 | 100.0% | 0.27 ms | 0.31 ms | 0.0% | 0 |
| 2,500 | MVC | 2,499.7 | 100.0% | 0.22 ms | 0.25 ms | 0.0% | 0 |
| 2,500 | WebFlux | 2,499.8 | 100.0% | 0.24 ms | 0.28 ms | 0.0% | 0 |
| 3,000 | MVC | 2,999.7 | 100.0% | 0.19 ms | 0.23 ms | 0.0% | 0 |
| 3,000 | WebFlux | 2,999.7 | 100.0% | 0.20 ms | 0.24 ms | 0.0% | 0 |

### Public Questions

| Target | Runtime | Successful RPS | Target met | p90 | p95 | Failed | Dropped |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | MVC | 692.6 | 69.3% | 3,605 ms | 3,775 ms | 0.0% | 1,918 |
| 1,000 | WebFlux | 116.2 | 11.6% | 5,000 ms | 5,000 ms | 75.9% | 5,381 |
| 1,500 | MVC | 725.6 | 48.4% | 4,301 ms | 4,327 ms | 0.0% | 8,584 |
| 1,500 | WebFlux | 223.2 | 14.9% | 5,000 ms | 5,000 ms | 58.3% | 11,803 |
| 2,000 | MVC | 727.7 | 36.4% | 4,223 ms | 4,244 ms | 0.0% | 16,043 |
| 2,000 | WebFlux | 281.4 | 14.1% | 5,000 ms | 5,000 ms | 48.2% | 19,186 |
| 2,500 | MVC | 723.0 | 28.9% | 4,242 ms | 4,317 ms | 0.0% | 23,707 |
| 2,500 | WebFlux | 284.3 | 11.4% | 5,000 ms | 5,000 ms | 47.4% | 26,729 |
| 3,000 | MVC | 693.5 | 23.1% | 4,332 ms | 4,351 ms | 0.0% | 31,590 |
| 3,000 | WebFlux | 288.8 | 9.6% | 5,000 ms | 5,000 ms | 47.1% | 34,249 |

Neither implementation sustains 1,000 successful public-question requests per second. MVC bounds application concurrency and leaves excess work at the load generator. WebFlux lets thousands of requests wait for R2DBC connections, so many requests time out after entering the application.

### Authenticated Studies

| Target | Runtime | Successful RPS | Target met | p90 | p95 | Failed | Dropped |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | MVC | 999.8 | 100.0% | 3.50 ms | 4.08 ms | 0.0% | 0 |
| 1,000 | WebFlux | 328.0 | 32.8% | 5,000 ms | 5,000 ms | 37.3% | 4,738 |
| 1,500 | MVC | 1,499.7 | 100.0% | 3.72 ms | 4.91 ms | 0.0% | 0 |
| 1,500 | WebFlux | 336.5 | 22.4% | 5,000 ms | 5,000 ms | 39.4% | 11,441 |
| 2,000 | MVC | 1,999.6 | 100.0% | 7.46 ms | 13.25 ms | 0.0% | 0 |
| 2,000 | WebFlux | 367.2 | 18.4% | 5,000 ms | 5,000 ms | 35.0% | 18,994 |
| 2,500 | MVC | 2,478.3 | 99.1% | 258.69 ms | 290.88 ms | 0.0% | 0 |
| 2,500 | WebFlux | 345.0 | 13.8% | 5,000 ms | 5,000 ms | 39.7% | 26,495 |
| 3,000 | MVC | 2,375.3 | 79.2% | 1,287 ms | 1,363 ms | 0.0% | 6,381 |
| 3,000 | WebFlux | 367.1 | 12.2% | 5,000 ms | 5,000 ms | 35.4% | 34,040 |

MVC's saturation knee is between 2,500 and 3,000 RPS for this endpoint. The current WebFlux implementation is already saturated below the first measured 1,000 RPS stage.

## Resource Evidence

| Endpoint / target | Runtime | JVM CPU median | RSS median | OS / JVM threads peak | DB acquired / pending max | HTTP active max |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Health / 3,000 | MVC | 26.8% | 893 MiB | 58 / 41 | 0 / 0 | 2 |
| Health / 3,000 | WebFlux | 32.8% | 743 MiB | 75 / 58 | 0 / 0 | 2 |
| Public / 1,000 | MVC | 172.9% | 928 MiB | 59 / 42 | 0 / 0 | 0* |
| Public / 1,000 | WebFlux | 357.9% | 907 MiB | 77 / 59 | 10 / 2,991 | 3,001 |
| Studies / 1,000 | MVC | 152.6% | 945 MiB | 60 / 42 | 3 / 0 | 5 |
| Studies / 1,000 | WebFlux | 318.0% | 952 MiB | 96 / 78 | 10 / 2,992 | 3,001 |
| Studies / 2,500 | MVC | 341.7% | 903 MiB | 59 / 42 | 10 / 5 | 16 |
| Studies / 2,500 | WebFlux | 298.2% | 950 MiB | 77 / 60 | 10 / 2,993 | 3,001 |

`ps` CPU can exceed 100% because multiple cores are used. RSS includes heap and JVM native memory. The overall median RSS was 903.0 MiB for MVC and 937.2 MiB for WebFlux, so the reactive implementation used 3.8% more memory over the complete run.

`*` Some saturated MVC public-question intervals produced only one telemetry sample after the request burst. The HTTP and k6 counters are complete, but zero-valued instantaneous MVC pool/activity entries for those intervals must not be interpreted as no database work.

## Current Bottlenecks

1. **Unbounded in-process waiting.** WebFlux reaches 3,001 active HTTP tasks while only 10 R2DBC connections are available. Non-blocking waiting avoids one thread per request, but it does not create database capacity or apply useful backpressure.
2. **Query amplification.** The public-question path fetches translations one question at a time for up to 20 IDs. Combined with the search, visibility, question, author, and statistics reads, one HTTP request fans out into many database operations.
3. **Cancellation correctness.** Each WebFlux round emitted Netty `ByteBuf.release()` leak reports under timeout cancellation: 6, 11, and 10 reports. It also emitted 65, 64, and 58 `UnsupportedOperationException` messages after responses had already been committed. These are correctness defects exposed by overload, not benchmark noise.
4. **Allocation and GC pressure.** At 1,000 RPS, WebFlux allocated about 990 MiB/s for public questions and 1,501 MiB/s for studies, with far more GC activity than MVC. The measured throughput does not justify that additional work.

## Focused Bottleneck Localization

The initial 1,000-3,000 RPS sweep only proved that WebFlux was already saturated. A second run restarted the application before every stage, narrowed the authenticated-studies load to 100-600 RPS, and recorded JFR profiles. This removed backlog carried over from the preceding stage.

- WebFlux ref: `3c9a90706ea32928e9585717eec284421342995c`
- One round per stage, 3-second warm-up, 10-second measurement
- Same 4-core JVM, 512 MiB heap, 10-connection pools, and 100-study fixture
- Results: `backend/loadtest/results/20260722-bottleneck-profile/`

| Target | Runtime | Successful RPS | p95 | JVM CPU | Allocation | Pool acquired / pending max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 100 | MVC | 100.0 | 9.40 ms | 76.7% | 68.5 MiB/s | 1 / 0 |
| 100 | WebFlux | 100.0 | 21.29 ms | 175.1% | 347.2 MiB/s | 2 / 0 |
| 200 | MVC | 200.0 | 6.39 ms | 113.3% | 120.5 MiB/s | 1 / 0 |
| 200 | WebFlux | 198.4 | 23.21 ms | 259.3% | 698.5 MiB/s | 5 / 0 |
| 300 | MVC | 300.0 | 5.37 ms | 148.1% | 162.3 MiB/s | 1 / 0 |
| 300 | WebFlux | 299.8 | 91.28 ms | 340.5% | 1,026.9 MiB/s | 10 / 5 |
| 400 | MVC | 399.8 | 5.18 ms | 195.0% | 208.2 MiB/s | 2 / 0 |
| 400 | WebFlux | 368.2 | 780.94 ms | 405.5% | 1,254.3 MiB/s | 10 / 259 |
| 600 | MVC | 599.9 | 7.55 ms | 246.1% | 332.8 MiB/s | 3 / 0 |
| 600 | WebFlux | 341.2 | 1,798.39 ms | 402.9% | 1,169.6 MiB/s | 10 / 589 |

The WebFlux saturation knee starts between 200 and 300 RPS. At 100 RPS, before either pool is saturated, WebFlux already uses 2.3 times the CPU and 5.1 times the allocation rate of MVC. Increasing the R2DBC pool is therefore not the first fix: by 400 RPS the process already consumes all four configured cores.

### JFR Evidence

At 200 RPS, JFR sampled 9.02 GiB of WebFlux allocation versus 1.58 GiB for MVC during the same interval. The dominant WebFlux stacks were:

1. Kotlin reflection used by `KotlinInstantiationDelegate` while Spring Data materializes every `StudyEntity` row.
2. Spring Data R2DBC SQL rendering, named-parameter parsing, and `DatabaseClient` binding.
3. R2DBC PostgreSQL `DataRow` and `CommandComplete` decoding on Reactor Netty threads.
4. Reactor context and security-context propagation for each publisher.

At 400 RPS, backlog amplified the cost. JFR attributed large sampled allocations to `SqlRenderer.render`, `FluxOnAssembly.CheckpointLightSnapshot`, Kotlin reflection, and `DatabaseClient.bind`. About 88% of sampled thread allocation occurred on the R2DBC Reactor Netty event-loop threads. PostgreSQL CPU remained about 39%, cache hit was 100%, and no storage wait appeared, so the database server was not the limiting resource.

`pg_stat_database.xact_commit` also showed about 4.5-5.2 committed transactions per WebFlux request below saturation, compared with about 2.9-3.1 for MVC. That matches the two authentication reads plus the content, count, and pending-question reads being committed separately. This indicates that the reactive read-only transaction may not be consolidating this path as the MVC transaction does; confirm it with an explicit transaction trace before changing transaction semantics.

### Row-Count Control Experiment

The same 400 RPS test was repeated with `GET /api/v1/studies?limit=1` instead of `limit=100`.

| Page size | Runtime | Successful RPS | p95 | JVM CPU | Allocation | Pool acquired / pending max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 100 | MVC | 399.8 | 5.18 ms | 195.0% | 208.2 MiB/s | 2 / 0 |
| 100 | WebFlux | 368.2 | 780.94 ms | 405.5% | 1,254.3 MiB/s | 10 / 259 |
| 1 | MVC | 400.0 | 4.27 ms | 161.7% | 61.3 MiB/s | 2 / 0 |
| 1 | WebFlux | 399.9 | 16.53 ms | 277.2% | 336.0 MiB/s | 10 / 5 |

Reducing the page to one row removes the WebFlux collapse: p95 falls by 97.9%, allocation falls by 73.2%, and the 259-request pool queue disappears. This identifies the current 100-row materialization and response pipeline as the primary bottleneck. The current WebFlux request path still costs about 1.7 times the CPU and 5.5 times the allocation of MVC at page size one, so there is also substantial fixed per-request overhead.

### Measurement Caveat

The benchmark sets the request-logging category to `OFF`, but both implementations still capture response bodies and build the JSON log message before SLF4J drops it. JFR confirms regex redaction and logging-buffer work in both profiles. The row-count experiment therefore includes real request-logging overhead, while the R2DBC-specific stacks above still isolate the larger WebFlux cost. The logging filter should check the effective log level before body capture and formatting, then the focused sweep should be repeated.

### Priority Order

1. Replace the hot list query's generic `R2dbcEntityTemplate` entity materialization with an explicit projection and row mapper that selects only response fields.
2. Return content and total count from one SQL statement, for example with `count(*) over()`, instead of concurrent content and count publishers.
3. Combine the session and device authentication reads, and verify why the service read-only transaction does not consolidate the three study reads.
4. Make request-body/response-body capture conditional on the effective log level; avoid formatting a message that cannot be emitted.
5. Add bounded admission around database-backed routes only after reducing per-request work. Reject overload quickly instead of allowing hundreds of pending pool acquisitions.
6. Keep the pool at 10 during optimization. Raising it while four cores are already saturated is likely to increase contention and tail latency.

## Required Follow-Up

1. Batch the translation lookup and audit all request paths for sequential N+1 queries.
2. Apply the focused studies-query optimizations in the priority order above.
3. Add a bounded admission limit aligned with the database pool, with an explicit short queue and overload response instead of thousands of five-second waits.
4. Reproduce and fix R2DBC cancellation leaks before another capacity comparison.
5. Repeat the 100-600 RPS focused sweep after each optimization, then rerun from a separate load-generator host for production capacity planning.

The initial report and dashboard are under `backend/loadtest/results/20260722T075419Z-mvc-vs-r2dbc/`. The focused sweep is under `backend/loadtest/results/20260722-bottleneck-profile/`, and the one-row control is under `backend/loadtest/results/20260722-bottleneck-limit1/`. Generated result files remain git-ignored; this document is the checked-in benchmark record.
