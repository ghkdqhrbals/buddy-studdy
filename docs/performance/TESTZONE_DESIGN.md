# BuddyStudy TestZone Design

## Decision

**Primary tool: k6.** k6 is the primary tool because its open arrival-rate
model separates requested RPS from response latency, its JavaScript scenarios
and thresholds are easy to review and automate, and the project remains
actively maintained. nGrinder remains a secondary closed-loop tool for
sustained-load and maximum-1,000-VUser cross-validation.

The two tools answer different questions:

| Tool | Primary question | Load model | BuddyStudy use |
| --- | --- | --- | --- |
| k6 | Can the API sustain an externally fixed target RPS? | Open loop | Capacity sweep, saturation point, regression gate |
| nGrinder 3.5.9-p1 | What throughput and recovery occur with a fixed active population? | Closed loop | 25-1,000 VUser sustained and soak cross-check |

nGrinder is pinned, private, and stopped after testing because its upstream
repository was archived in September 2025. It is not the source of truth for
open-loop capacity.

## Industry Evidence

The design uses recurring practices found in recent engineering publications:

| Source | Practice adopted |
| --- | --- |
| [KakaoPay TestZone, 2024](https://tech.kakaopay.com/post/perftest_zone/) | Self-service isolated projects, configurable resources, reusable DB/Redis fixtures, k6 and nGrinder support, execution history, and a monitoring dashboard |
| [Kakao messaging stress testing, 2026](https://tech.kakao.com/posts/822) | Production-shaped traffic mixes, over-provisioned load generators, one experiment/one variable, layered analysis from endpoint to system/JVM/custom queues, and numeric decision records |
| [LY/LINE VOOM Milvus verification](https://techblog.lycorp.co.jp/en/large-scale-vector-db-for-real-time-recommendation-in-line-voom) | API-specific performance and chaos scenarios, recovery checks, and throughput/latency/resource comparisons |
| [LINE k6 executors](https://engineering.linecorp.com/en/blog/performance-test-in-jenkins-run-dynamic-pod-executors-in-kubernetes-parallelly/) | Separate scalable generators and centralized result collection |
| [Uber Ceilometer, 2025](https://www.uber.com/en-GB/blog/ceilometer-ubers-adaptive-benchmarking-framework/) | Portable containerized benchmark definitions, standardized JSON results, environmental context, historical A/B comparison, and resource/cost evidence |
| [Uber shifting E2E left, 2024](https://www.uber.com/us/en/blog/shifting-e2e-testing-left/) | Candidate and placebo/baseline executions, history, failure aggregation, and actionable diagnostics |
| [Google Cloud application benchmarking](https://cloud.google.com/blog/products/containers-kubernetes/benchmarking-how-end-users-perceive-an-applications-performance/) | Complete user journeys, continuous regression execution, and realistic load/scaling behavior |

No directly applicable Netflix or YouTube engineering publication from the
reviewed 2024-2026 window was found. Their names are not used to imply support
for a design they did not document. YouTube is represented only by recent
Google engineering guidance.

## Test Contract

Each execution records:

- project, purpose, owner, timestamp, runtime refs, tool and profile
- target and generator machine specifications and clock skew
- JDK, container images, CPU visibility, heap, database pool, fixture version
- scenario, endpoint, query, authentication and response contract
- target load, achieved requests, successful RPS, failures, timeouts, dropped
  requests, and latency percentiles
- application CPU/RSS/heap/non-heap/direct memory/allocation/GC/thread metrics
- Tomcat worker or Reactor Netty queue/event-loop metrics
- PostgreSQL CPU/memory/connections/active/waiting/cache-hit metrics
- Redis CPU/memory/connections/operations/eviction/rejection metrics
- generator CPU/memory/network/errors so invalid tests are excluded

`/health` is only a local startup probe. It is never a performance scenario or
report series. Capacity conclusions are API-specific:

1. `public-questions`
2. `studies`
3. optional `mobile-read-mix`, reported separately

## Latency Semantics

The dashboard distinguishes these values:

- **all-request p95**: k6 `http_req_duration`; includes successful requests,
  HTTP failures, validation failures, and client timeouts
- **successful-only p95**: `successful_request_duration`; recorded only after
  response status and required JSON fields pass
- **timeout rate/count**: explicit k6 timeout metrics
- **failure rate**: transport and response-contract failures among started
  requests
- **dropped iterations**: arrivals k6 could not start, separate from failures

The July 22 historical WebFlux Studies run has all-request p95 near 5,000 ms at
every target. This means at least the p95 tail reached the configured client
timeout. It does **not** mean every request timed out: successful throughput
remained roughly 328-367 RPS, while 35-39% of started requests failed and many
scheduled arrivals were dropped. Successful-only p95 was not captured in that
historical run and must remain unknown.

## Validity And Verdict

A measurement is invalid when the load generator is the bottleneck:

- generator CPU p95 above 80%
- memory use above 95%
- NIC errors/drops
- configured network capacity above 95%
- mandatory telemetry missing

The first stage is saturated when any condition holds:

- successful throughput is below 95% of target
- error rate is above 1%
- dropped requests are non-zero
- PostgreSQL waiting queries appear

The immediately preceding stage is sustainable only with error below 0.1% and
no dropped/rejected request. A regression is reported for at least 5% lower
successful RPS or at least 10% higher p95 when all three standard rounds agree.
k6 and nGrinder disagreement is reported as inconclusive, not averaged away.

## Site Information Architecture

The monitoring site has four stable destinations in a left navigation:

1. API Logs
2. API Performance
3. Server Dashboard
4. TestZone

TestZone provides:

- project and execution selection
- selected-run verdict and evidence-backed findings
- requested versus successful throughput
- all-request p95 with the timeout boundary
- successful-only p95 and timeout rate when collected
- CPU and RSS comparison
- immutable execution history
- stage-level evidence table
- a reviewed command generator with a hard 1,000-VUser limit

The site is a read-only result catalog. Actual execution remains in the
versioned load-test harness so browser compromise cannot start load.

## Current Studies Finding

The historical WebFlux/R2DBC Studies result is a saturation result, not a
valid latency comparison:

- 10 R2DBC connections were fully occupied
- roughly 2,990 connection acquisitions queued
- roughly 3,001 HTTP tasks stayed active without an admission limit
- process CPU and allocation also saturated around the 200-300 RPS region
- at 1,000-3,000 requested RPS, successful throughput plateaued near
  328-367 RPS

The next diagnostic run must add bounded admission/concurrency and then repeat
the same API, fixture, pool, CPU and heap conditions. Increasing the pool alone
would change multiple variables and would not isolate the queueing problem.
