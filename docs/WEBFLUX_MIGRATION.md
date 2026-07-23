# Backend WebFlux Runtime

## Current Decision

BuddyStudy runs Spring WebFlux on Reactor Netty and uses Kotlin coroutines at the application boundary. Runtime PostgreSQL access is R2DBC, so authentication, controller, service, and database work can suspend without reserving one platform thread while waiting for database I/O.

This document describes the HTTP runtime. The persistence design, transaction semantics, migration risks, and operational tuning are documented in [R2DBC_MIGRATION.md](R2DBC_MIGRATION.md).

## Request Path

```mermaid
sequenceDiagram
    participant C as Client
    participant N as Reactor Netty
    participant S as WebFlux Security
    participant A as Suspend Use Case
    participant R as R2DBC Pool
    participant P as PostgreSQL

    C->>N: HTTP request
    N->>S: WebFilter chain
    S->>R: Suspend session/device query
    R->>P: Non-blocking protocol I/O
    P-->>R: Rows
    R-->>S: Principal
    S->>A: Suspend controller/use case
    A->>R: Suspend query or transaction
    R->>P: Non-blocking protocol I/O
    P-->>R: Result
    R-->>A: Resume coroutine
    A-->>N: Response
    N-->>C: HTTP response
```

No `webflux-blocking-*` executor exists in the current runtime. Netty event-loop threads initiate asynchronous work and are released while PostgreSQL is processing it.

## MVC And WebFlux

| Concern | Spring MVC with JDBC | Current WebFlux with R2DBC |
| --- | --- | --- |
| HTTP server | Servlet request workers | Reactor Netty event loops |
| Waiting for DB | Request worker remains blocked | Coroutine suspends; driver continues asynchronously |
| Security context | Usually thread-local | Reactor context |
| Transactions | Thread-bound JDBC transaction | Reactive transaction context |
| Request body | Servlet stream/wrapper | `DataBuffer` publisher/decorator |
| Primary concurrency bound | Servlet threads and JDBC pool | R2DBC connections, pending acquisition, DB capacity |
| Overload symptom | Worker/connection queue growth | Pending pool acquisition, tail latency, timeouts |

WebFlux does not make CPU-heavy work faster. It also does not increase PostgreSQL query capacity. Its main benefit is avoiding a platform thread per waiting request when the complete call path is non-blocking.

## Boundaries

- PostgreSQL request-path I/O uses R2DBC.
- Redis publication and ACK use reactive APIs. Redis Stream `XREAD BLOCK` is deliberately confined to Spring scheduler threads, then coroutine listeners launch suspend handlers.
- Request and response logging streams bounded payload previews without joining unbounded bodies.
- Flyway uses JDBC only during startup because migration execution is not request traffic.
- Google OAuth, LibreTranslate, and Slack use Reactor Netty `WebClient`; APNs uses Java `HttpClient.sendAsync`.
- SMTP is the remaining synchronous integration and is explicitly isolated on `Dispatchers.IO`.
- Permission seed initialization uses `runBlocking` during application startup only.

## Transaction Boundaries

WebFlux transactions are carried in Reactor context, not thread-local state. A coroutine can resume on another thread and still use the same R2DBC transaction, provided the work remains in the structured suspend call chain.

- Do not wrap external HTTP, OpenAI, SMTP, APNs, or Redis publication in a database transaction.
- Use a dedicated write manager for multi-table mutations so the atomic boundary is visible and testable.
- Do not launch an application coroutine from inside a transaction for database work.
- Register non-durable integrations with `afterReactiveCommit`; use a transactional outbox when losing an event during a process crash is unacceptable.
- R2DBC entities are detached values. Every mutation requires an explicit `save` or update statement.

The scheduled-question flow claims work in a short transaction, releases the connection while OpenAI runs, and completes or fails in a second short transaction. The claim has an expiry, allowing another worker to recover work after a process crash.

## Verification

Use these checks after runtime changes:

```sh
cd backend
./gradlew test
./gradlew :tutor:bootJar
./gradlew :tutor:dependencies --configuration runtimeClasspath
```

The runtime classpath must include WebFlux and R2DBC and must not include Spring MVC, Spring Data JPA, Hibernate, or Hikari. The PostgreSQL JDBC driver remains intentionally present for Flyway startup migrations.

The executable comparison harness is in [backend/loadtest/README.md](../backend/loadtest/README.md). Compare MVC/JDBC and WebFlux/R2DBC using the same dataset, JVM limits, PostgreSQL instance, Redis instance, warm-up, and arrival-rate stages. A comparison based only on average response time is not sufficient.
