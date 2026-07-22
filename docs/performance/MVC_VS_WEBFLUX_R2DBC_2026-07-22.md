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

## Required Follow-Up

1. Batch the translation lookup and audit all request paths for sequential N+1 queries.
2. Add a bounded admission limit aligned with the database pool, with an explicit short queue and overload response instead of thousands of five-second waits.
3. Reproduce and fix R2DBC cancellation leaks before another capacity comparison.
4. Run a finer 100-900 RPS sweep for WebFlux to identify its actual saturation knee.
5. After fixes, rerun from a separate load-generator host for production capacity planning.

The raw report and interactive dashboard are under `backend/loadtest/results/20260722T075419Z-mvc-vs-r2dbc/` locally. Generated result files remain git-ignored; this document is the checked-in benchmark record.
