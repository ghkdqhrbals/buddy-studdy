# BuddyStudy Portfolio And Interview Guide

This document is the source of truth for explaining BuddyStudy in a portfolio
review or technical interview. It separates shipped behavior, measured results,
and planned optimization so claims remain defensible.

## 30-Second Explanation

BuddyStudy is an iOS AI tutor that turns a topic and difficulty into short
questions, preserves answer drafts, grades answers through OpenAI, and converts
the resulting records into topic-level learning statistics. The SwiftUI client
does not call OpenAI directly. A Kotlin Spring backend owns identity, terms,
question generation, grading, schedules, APNs, MySQL data, Redis Streams,
and monitoring. I also built an API-log and server dashboard, a repeatable k6
benchmark, and separate deployment pipelines for the backend, admin,
monitoring, and external health monitor.

## Three-Minute Explanation

The product loop is `question -> draft -> grading -> record -> topic insight`.
The main UX requirement is that a scheduled or synchronized question must never
replace an answer the user is currently writing. The app therefore treats the
backend as the source of truth while retaining lightweight local draft and
recovery state.

The backend uses a multi-module hexagonal structure:

- `domain`: entities and domain concepts;
- `application`: inbound use cases and orchestration;
- `infra`: web, R2DBC, Redis, OpenAI, APNs, logging, and persistence adapters;
- `tutor`: executable composition root.

Question and grading writes are transactional. Events that must reach Redis are
first appended to an outbox in the same R2DBC transaction. A polling dispatcher
claims rows with `FOR UPDATE SKIP LOCKED`, publishes with an idempotency key, and
retries with a lease and exponential backoff. This gives at-least-once delivery
without pretending that Redis publication and MySQL commit are one atomic
operation.

For performance, I compared MVC/JDBC and WebFlux/R2DBC under the same CPU, heap,
database fixture, and 10-connection pool at 1,000-3,000 requested RPS. The
public-questions and authenticated-studies APIs are measured independently so
one endpoint cannot hide another endpoint's saturation. The current reactive
studies path admitted thousands of requests into a 10-connection pool and paid
high row-materialization and allocation costs. A controlled `limit=100` versus
`limit=1` experiment reduced p95 by 97.9% and allocation by 73.2%, identifying
response materialization as the primary bottleneck. The result is an
optimization plan based on evidence, not a claim that one framework is
universally faster.

## Product Capabilities

### Study

- Topic, difficulty, and interval configuration.
- Manual and server-scheduled question generation.
- Hint reveal without exposing the full grading result.
- Automatic answer-draft preservation.
- Backend OpenAI generation and grading.
- Maximum pending-question policy.
- Safe activation so new synchronized questions do not steal the current draft.

### Records

- Ungraded items first.
- Search and pagination for a data set that can grow toward 10,000 records.
- Question, answer, score, feedback, explanation, and state detail.
- Continue answering an ungraded item.
- Delete and refresh from the backend source of truth.

### Statistics

- Period filters and topic-first interpretation.
- Topic normalization across case, spacing, punctuation, underscores,
  hyphens, width, diacritics, and simple camelCase.
- Difficulty and score combined into an estimated 1-10 ability range.
- Search, sort, pagination, topic detail, and trend visualization.
- Incremental MySQL read model using dirty keys rather than rebuilding all
  history after every answer.

The write transaction adds only the affected
`(user_id, stat_date, topic_key, difficulty_level)` to
`user_stats_dirty_keys`. Workers claim bounded batches with
`FOR UPDATE SKIP LOCKED` and rebuild a bucket from the question source of truth.
Deleting a dirty marker compares the observed `updated_at`, so a concurrent
answer does not disappear behind an older refresh. Reprocessing is safe because
the bucket is recomputed rather than incremented.

### Cache Boundaries

Cache only data that can be reconstructed:

| Data | Location | Invalidation |
| --- | --- | --- |
| Active answer draft | `SettingsStore`, not a disposable cache | grading or explicit discard |
| Record pages and view models | iOS memory | delete and successful synchronization |
| Avatar catalog and profile config | iOS local cache | profile save, logout, catalog version |
| Email verification code | Redis TTL | expiry or successful verification |

The public-question response contains viewer-specific fields such as
`likedByMe`. A shared response cache can leak one viewer's state into another,
so the proposed seven-second list cache is not described as a deployed feature.

### Avatar Image Cost

The current default is a Reddit-style avatar builder, not a user-photo upload
pipeline. MySQL stores catalog item keys, categories, compatibility,
z-index, unlocks, and a compact user config. The iOS app composes fixed slots
from bundled assets and locally cached catalog data. This avoids per-user image
blobs, CDN variants, and repeated profile-image downloads. Photo upload and
64/256 on-the-fly resizing remain future design options, not current behavior.

### Identity, Terms, And Community

- Device registration before an account is linked.
- Google identity linked to the current device.
- One app-wide authenticated session state.
- Required and optional terms versions stored by the backend.
- Error-code policy routes authentication and terms requirements to dedicated
  flows rather than repeated generic popups.
- Public profile, public questions, likes, comments, and reports.
- User and device-aware notification preferences.

### Push And Background Work

- APNs token registration through the iOS remote-notification bridge.
- Generated question is stored before a push job is published.
- Redis Stream consumer sends APNs.
- Push arrival synchronizes quietly.
- Navigation occurs only after an explicit notification tap or reply.
- A device without an APNs token can still use manual generation and grading.

## Architecture

```text
SwiftUI iOS
  -> HTTPS API
  -> Cloudflare / Nginx
  -> Spring WebFlux + Kotlin coroutines
  -> application use cases
  -> outbound ports
  -> MySQL (R2DBC), Redis Streams, OpenAI, APNs
```

### Why Hexagonal Boundaries

The purpose is not naming layers. It is to keep policy testable and to prevent
framework details from becoming the domain API.

- Controllers depend on controller-facing ports.
- Application services implement inbound use cases.
- Lower-level domain services depend on outbound ports.
- Adapters implement those ports.
- Composition services may combine lower-level use cases.

This makes a transport or persistence change local, while also making invalid
cross-layer dependencies visible during review.

### Why Kotlin Coroutines With WebFlux

Suspending functions keep sequential business logic readable while WebFlux and
R2DBC keep I/O waits non-blocking. Coroutines do not create database capacity
and do not automatically enforce backpressure. Admission control, query count,
row mapping, transaction boundaries, cancellation, and pool sizing remain
explicit engineering responsibilities.

### Transaction Model

R2DBC transactions are propagated through Reactor context, not a thread-local
bound JDBC connection. A coroutine can resume on another thread and still
participate in the same reactive transaction. Spring Data Relational has no JPA
managed persistence context, dirty checking, or lazy loading, so mutations must
be saved explicitly.

### Event Delivery

The transactional outbox addresses the dual-write problem:

```text
business write + outbox row (one MySQL transaction)
  -> polling claim with SKIP LOCKED
  -> Redis Stream publish
  -> mark published
```

A crash can happen after Redis accepts an event and before MySQL marks the
row published. Consumers therefore deduplicate by `(event_type, event_id)`.

### Why Redis Streams

Redis already served short-lived authentication state and was already part of
the operated infrastructure. The current workload needs append-only ordering,
consumer groups, pending entries, acknowledgements, and replay. Redis Streams
provides those properties without adding a Kafka cluster and its operational
surface at the current traffic and team size.

The current guarantee comes from:

- one MySQL transaction for the business row and outbox row;
- a unique `(event_type, event_id)` producer key;
- leased `SKIP LOCKED` claims and exponential retry;
- event-id deduplication in consumers;
- at-least-once delivery rather than a false exactly-once claim.

Redis 8.6 introduced producer-side `XADD IDMP` and `IDMPAUTO`. Those options can
reduce duplicate stream entries after a verified Redis and client upgrade, but
they are a future defense. They are not the source of the current guarantee and
must not be attributed to Redis 8.2.

## Performance Engineering

### Test Method

- k6 constant-arrival-rate open-loop workload.
- nGrinder 3.5.9-p1 closed-loop workload from 25 to 1,000 simultaneous
  VUsers, with the 1,000-client stage split into four processes and 250
  threads per process.
- Public questions and authenticated studies are measured as separate API
  scenarios. The mobile read mix is optional secondary evidence, and the
  health endpoint is excluded from capacity conclusions.
- 1,000, 1,500, 2,000, 2,500, and 3,000 requested RPS.
- Three alternating-order rounds, median reported.
- Same MySQL fixture, Redis, 4 visible CPUs, 512 MiB heap, and 10 DB
  connections.
- p50, p90, p95, p99, HTTP RPS, successful RPS, failure rate, and dropped work.
- CPU, RSS, heap, direct memory, thread counts, DB pool, MySQL, Redis, GC,
  and allocation telemetry.
- JFR and a row-count control experiment for bottleneck localization.

The reusable harness keeps scenario definitions, fixtures, credentials,
normalization, and report metadata shared across both tools. The preserved
nGrinder result is currently a one-VUser wiring smoke, so it validates
controller/agent/script/result automation but is not used as a capacity result.
The standard profile is defined at 25, 50, 100, 200, 400, 600, 800, and 1,000
simultaneous VUsers. Only repeated standard runs may support an MVC/WebFlux
sustained-load conclusion.

### Defensible Results

| Observation | Result |
| --- | --- |
| MVC authenticated studies | 2,478 successful RPS at a 2,500 RPS target |
| Current WebFlux authenticated studies | Saturation started below 1,000 RPS in the initial sweep |
| WebFlux studies at 1,000 target RPS | 328.0 successful RPS, p95 5,000.36 ms, 37.322% failure |
| Reactive DB queue | About 2,992 pending acquisitions behind a 10-connection pool |
| Overall median RSS | 903.0 MiB MVC, 937.2 MiB WebFlux |
| Focused 400 RPS, 100 rows | WebFlux p95 780.94 ms, allocation 1,254.3 MiB/s |
| Focused 400 RPS, 1 row | WebFlux p95 16.53 ms, allocation 336.0 MiB/s |

### What The Experiment Proved

- The isolated studies result attributes saturation to that API rather than a
  synthetic route or a mixed workload.
- Non-blocking waiting did not increase MySQL capacity.
- Unbounded admission converted a small pool into thousands of in-process
  waiters and five-second timeouts.
- The 100-row entity materialization and response path was the largest measured
  cost.
- Increasing the pool first would be unsafe because four CPU cores were already
  saturated.

### Optimization Order

1. Use an explicit projection and row mapper for hot list responses.
2. Return content and count from one statement where appropriate.
3. Remove authentication and query amplification.
4. Skip request/response body capture when the log level cannot emit it.
5. Fix cancellation correctness.
6. Add a short, bounded admission queue aligned with actual DB capacity.
7. Rerun the focused sweep after each change from a separate load-generator
   host before calling the result production capacity.

## Reliability And Data Consistency

- Draft state is protected from remote replacement.
- Backend records are the source of truth; the app uses an in-memory view cache.
- Statistics use idempotent dirty-key recomputation.
- Dirty rows are claimed in bounded batches with `SKIP LOCKED`.
- A concurrent update preserves the dirty marker by comparing `updated_at`.
- Outbox delivery is at-least-once with consumer deduplication.
- APNs generation stores data before delivery.
- Scheduled health is checked externally by Cloudflare Cron rather than by a
  deployment workflow that can only observe the deploy moment.

## Security

### Network Segmentation

- Public HTTPS services are routed through Cloudflare Tunnel and Nginx.
- MySQL and Redis are not intended for direct public administration.
- Operator access uses a Cloudflare WARP private `/32` route.
- Compatibility TCP access requires a local `cloudflared access tcp` proxy.
- Loki and Grafana bind to `127.0.0.1`; the authenticated dashboard proxy is the
  external entry point.

### Identity And Secrets

- Access tokens bind `user_id` and `device_id`.
- Server-side authorization resolves the stored user-device relationship.
- OpenAI calls happen only in the backend.
- Runtime secrets are supplied by AWS Secrets Manager during the backend deploy.
- Logs redact configured secrets and authorization material.
- Terms and permissions are backend policy; UI behavior is selected from stable
  API error codes.

### Deployment Isolation

- GitHub-hosted runners compile the backend and publish a GHCR image.
- The EC2 runner is deploy-only and does not compile or build images.
- Backend, admin, monitoring, and health monitor have separate workflows.
- GitHub Actions do not call runtime health endpoints.
- Cloudflare Cron owns readiness checks and Slack alerts.

## Observability

### Request Tracing

Each API exchange includes a request ID, method, path, status, duration, client
IP, sanitized headers, request body, and response body. Related logs can be
collected by request ID in the API log dashboard.

### Dashboards

- API logs with filters, pagination, inline request/response, stack trace, and
  related logs.
- Endpoint p50, p90, p95, and p99 performance.
- Golden signals: traffic, latency, errors, and saturation.
- Runtime and host CPU, memory, disk, network, threads, GC, R2DBC pool, and
  Reactor Netty queues.

The production executable is a GraalVM Native Image. MXBean collectors are
best-effort and isolated: an unsupported counter marks the sample partial but
does not erase valid `/proc`, R2DBC, or Netty metrics.

## Testing Strategy

| Layer | Examples |
| --- | --- |
| Swift unit | models, storage policy, error routing, topic grouping, API decoding |
| iOS build | generic iOS device build with signing disabled |
| Real device | push, notification permission, background refresh, login, visible UX |
| Backend unit | domain policies, use cases, adapters, parsers |
| Backend integration | R2DBC persistence, transactions, migrations, HTTP contracts |
| Monitoring | log parser, LogQL query builders, runtime metric formatting |
| Infrastructure | Cloudflare Worker configuration and behavior tests |
| Load | k6 RPS sweeps, telemetry, JFR, controlled experiments |

## Common Interview Questions

### “Why did you choose WebFlux?”

The workload includes outbound OpenAI, APNs, Redis, and database I/O, and
coroutines keep the orchestration readable. I did not assume WebFlux made the
system faster. The benchmark showed the current DB path was worse under load,
which led to a concrete optimization and admission-control plan.

### “Why not increase the DB pool?”

At the measured failure point, all four application CPUs were already saturated
and allocation exceeded 1 GiB/s. More connections would add database and
application contention without removing row materialization. The query path
must become cheaper before pool tuning.

### “How do you prevent event loss?”

The event is written to a MySQL outbox in the business transaction. A
dispatcher publishes it later. This prevents the business row from committing
without a durable publication intent. Delivery is at-least-once, so consumers
deduplicate.

### “Why Redis Streams instead of Kafka?”

Redis was already operated, and the required semantics were consumer groups,
ACK, pending-entry recovery, and replay rather than long-term event retention
or broad multi-team streaming. Transactional outbox solves the MySQL/Redis
dual-write boundary. The cost is at-least-once delivery and explicit consumer
idempotency. Kafka becomes reasonable when retention, partition scale, replay
volume, or organizational fan-out exceeds this simpler operating model.

### “Does Redis XADD make publication idempotent?”

The deployed design does not rely on it. Redis 8.6 added `XADD IDMP` and
`IDMPAUTO`, but the current stack uses the outbox unique event key and consumer
deduplication. After a verified 8.6 and client upgrade, producer-side
idempotency can become an additional layer, not a replacement for transaction
and consumer correctness.

### “How does `@Transactional` work with coroutines?”

With R2DBC, Spring binds the transaction to Reactor context. It does not require
the coroutine to remain on one OS thread. It also does not create a JPA
persistence context, so changed entities must be explicitly saved.

### “How is private infrastructure protected?”

Public HTTP is tunneled by hostname, while DB and Redis administration use a
Cloudflare WARP private route. The observability stores bind locally, and only
the authenticated proxy is exposed. Runtime secrets come from AWS Secrets
Manager rather than the repository.

### “What would you improve next?”

First, optimize the hot R2DBC list projection and query count. Then fix
cancellation issues, add bounded admission, and repeat the focused benchmark
from an independent load host. On the product side, add explicit multi-device
draft conflict UX and export for records and statistics.

## Evidence Index

- Product behavior: `docs/PRD.md`
- Architecture and data flow: `docs/ARCHITECTURE.md`
- R2DBC details: `docs/R2DBC_MIGRATION.md`
- WebFlux details: `docs/WEBFLUX_MIGRATION.md`
- Performance report: `docs/performance/MVC_VS_WEBFLUX_R2DBC_2026-07-22.md`
- Load harness: `backend/loadtest/README.md`
- Dual-tool normalized smoke:
  `backend/loadtest/results/verification-smoke-dual-v5/normalized-results.json`
- Redis Stream idempotency:
  `https://redis.io/docs/latest/develop/data-types/streams/idempotency/`
- Runtime monitoring: `docs/observability/runtime-metrics.md`
- Cloudflare private access: `deploy/cloudflared/README.md`
- Deployment ownership: `docs/deploy-repo-template/deployment-modules.md`
- Monitoring setup: `monitoring/README.md`
