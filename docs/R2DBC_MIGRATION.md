# R2DBC Persistence Migration

## Decision

BuddyStudy runtime persistence uses Spring Data R2DBC with the MySQL R2DBC driver. The migration covers the complete database request path:

- WebFlux controllers and security filters call suspending inbound ports.
- Application services implement suspending use cases.
- Outbound persistence ports expose suspending operations.
- Persistence adapters use coroutine repositories, `R2dbcEntityTemplate`, or `DatabaseClient`.
- MySQL connections come from `r2dbc-pool`.
- Reactive transactions are propagated through Reactor context.

JPA, Hibernate, Hikari, `JdbcTemplate`, and `EntityManager` are not used for runtime requests. Flyway deliberately keeps a JDBC connection for startup-only schema migration.

## Why The Whole Path Matters

Changing only a controller return type to `Mono` or marking a blocking repository call as `suspend` does not make database I/O non-blocking. Non-blocking behavior requires the network driver and every application boundary on the call path to support asynchronous completion.

### Before: JDBC/JPA

```mermaid
sequenceDiagram
    participant C as Client
    participant T as Request or worker thread
    participant J as JPA/JDBC
    participant P as MySQL

    C->>T: Request
    T->>J: Repository call
    J->>P: Blocking socket operation
    Note over T,P: Thread remains occupied while MySQL works
    P-->>J: Rows
    J-->>T: Managed entity
    T-->>C: Response
```

### After: R2DBC

```mermaid
sequenceDiagram
    participant C as Client
    participant E as Netty event loop
    participant K as Coroutine
    participant R as R2DBC driver
    participant P as MySQL

    C->>E: Request
    E->>K: Start suspend handler
    K->>R: Query
    R->>P: Async protocol I/O
    K-->>E: Suspend
    Note over E,P: Event-loop thread can process other connections
    P-->>R: Rows
    R-->>K: Resume
    K-->>E: Response value
    E-->>C: Response
```

## Behavioral Differences

| Area | JPA/JDBC | R2DBC in BuddyStudy | Required engineering behavior |
| --- | --- | --- | --- |
| I/O wait | Calling thread blocks | Coroutine suspends | Do not call JDBC or blocking clients on the event loop |
| Entity lifecycle | Persistence context manages entities | Returned objects are detached values | Call `save` or explicit SQL after mutation |
| Dirty checking | Flushes changed managed fields | Not available | Persist every intended update explicitly |
| Lazy loading | Proxies can load relations later | Not available | Query required data explicitly and batch related reads |
| Cascades | ORM cascade rules | No JPA cascades | Define write ordering and deletion SQL explicitly |
| Transactions | Thread-local connection | Reactor-context connection | Keep work in a reactive/suspend transaction chain |
| Repository API | `JpaRepository` | `CoroutineCrudRepository` plus explicit clients | Use `suspend`/`Flow`; avoid hidden blocking adapters |
| SQL control | ORM-generated SQL | Derived queries or explicit SQL | Inspect indexes, row counts, and query plans directly |
| Collection parameters | ORM expands collections | Driver binding is database-specific | Expand bind markers safely; never concatenate values |
| Generated IDs | ORM updates managed entity | Insert result must be returned/mapped | Use repository/template insert semantics and return saved values |
| Optimistic locking | ORM annotations/version checks | Must be modeled explicitly | Add version predicates where concurrent writes require them |

The lack of dirty checking is the largest correctness difference. Code such as `entity.markDeleted()` changes only memory until the adapter executes `save(entity)` or an explicit update statement.

## Transaction Semantics

Suspending `@Transactional` methods are backed by Spring's reactive transaction manager. The active connection is stored in Reactor context, not in a thread-local. A coroutine may resume on another thread while retaining the same transaction context.

Rules:

1. Keep all database work inside suspend/reactive calls. A nested JDBC call cannot participate safely in the reactive transaction.
2. Do not launch unstructured coroutines inside a transaction. A new scope can lose transaction context and outlive commit or rollback.
3. Keep transaction scopes short. Non-blocking execution removes blocked threads, but a long transaction still occupies one database connection and may retain locks.
4. Publish external events after commit. `afterReactiveCommit` registers a reactive transaction synchronization so Redis notifications are not visible before the corresponding rows commit.
5. Prefer a durable transactional outbox when an event must survive process failure between database commit and broker publish. An after-commit callback preserves ordering but is not a durable delivery guarantee.

BuddyStudy keeps external work out of write transactions:

- Google token verification, OpenAI generation/grading, translation, SMTP, APNs, and Slack calls execute before or after the database transaction.
- `AuthenticatedLoginManager`, `QuestionCreationWriteManager`, `ScheduledQuestionWriteManager`, and `StudyRecordWriteManager` own short, readable atomic write boundaries.
- Question and record mutations explicitly save every changed entity. There is no persistence context or dirty checking to flush in-memory mutations.
- Record answer/delete/publicity writes lock the selected question row before applying a state transition, preventing concurrent requests from persisting changes from the same stale snapshot.
- Question creation persists the question, stats row, coverage state, embedding, quota finalization, and schedule state in one transaction. A failure in a later persistence step rolls back the earlier inserts.
- Search, notification, and stream publication is registered after commit and each publication is failure-isolated so one integration failure does not suppress another.

Scheduled question processing uses a two-phase lease:

1. A short transaction claims due study IDs with `FOR UPDATE SKIP LOCKED` and stores `schedule_claimed_until`.
2. OpenAI and other external work executes without holding a database connection or row lock.
3. A short completion transaction persists all generated-question state and clears the lease.
4. A failure transaction refunds the quota reservation, stores retry state, and clears the lease.
5. If a process dies after claiming, another worker may reclaim the study after the bounded lease expires.

## Persistence Adapter Choices

- Use `CoroutineCrudRepository` for simple single-table CRUD and derived queries.
- Use `R2dbcEntityTemplate` when entity mapping is useful but the operation needs explicit criteria, pagination, or insert/update control.
- Use `DatabaseClient` for joins, aggregates, upserts, locking/claim queries, or database-specific SQL.
- Keep SQL in outbound persistence adapters. Application services depend on outbound ports rather than R2DBC APIs.
- Use parameter binding for all values. The shared indexed-binding helper expands collection arguments into individual bind markers.
- MySQL natural-key counters and read models use explicit upsert statements so concurrent updates do not rely on a read-then-insert race.

## Pool And Backpressure

Current defaults:

| Property | Default | Environment variable |
| --- | ---: | --- |
| Initial connections | 2 | `DB_POOL_INITIAL` |
| Maximum connections | 10 | `DB_POOL_MAX` |
| Maximum idle time | 30 minutes | `DB_POOL_MAX_IDLE_TIME` |
| Maximum acquisition wait | 5 seconds | `DB_POOL_MAX_ACQUIRE_TIME` |
| Runtime URL | `r2dbc:mysql://localhost:3306/buddystudy` | `R2DBC_DATABASE_URL` |

R2DBC allows many suspended requests, but MySQL still executes work through a finite number of connections. An unbounded number of accepted requests can therefore become an unbounded pending-acquisition queue. The pool, API timeout, scheduler batch size, and database connection limit must be tuned together.

Do not raise `DB_POOL_MAX` solely to improve RPS. More connections can increase context switching, lock contention, working-set memory, and query latency. First fix query plans and transaction duration, then increase concurrency only while MySQL CPU, I/O, locks, and memory remain healthy.

## Flyway And Schema Compatibility

Flyway remains JDBC because it runs synchronously before request serving. Configure it separately from runtime R2DBC:

```text
R2DBC_DATABASE_URL=r2dbc:mysql://db:3306/buddystudy
DATABASE_URL=jdbc:mysql://db:3306/buddystudy
DATABASE_USERNAME=...
DATABASE_PASSWORD=...
```

The custom runtime connection details resolve canonical Spring properties first, followed by deployment fallbacks:

1. `spring.r2dbc.url`, username, and password;
2. `R2DBC_DATABASE_*`;
3. converted JDBC `DATABASE_*` settings.

This ordering is required for Testcontainers and other runtime overrides. Flyway and R2DBC must point to the same database in integration tests; otherwise migrations can succeed against one database while application repositories query another.

The migration does not require a new schema format. Existing Flyway migrations remain the schema source of truth. `dev` enables Flyway by default; `prod` enables it only when `FLYWAY_ENABLED=true`.

## Remaining Blocking Boundaries

The database request path is non-blocking. Deliberate blocking boundaries are isolated from request and Reactor event-loop threads:

- Flyway JDBC migration: startup only.
- Permission seed `runBlocking`: startup only.
- Redis Stream `XREAD BLOCK`: invoked only by Spring `@Scheduled` polling threads; stream publication and ACK use reactive Redis APIs.
- JavaMail SMTP: the library is synchronous, so delivery runs on `Dispatchers.IO`.

Google OAuth, LibreTranslate, Slack, APNs, Redis request operations, and MySQL runtime access use asynchronous clients. A new blocking integration must either be replaced with an asynchronous client or be explicitly isolated and load-tested.

## Performance Expectations

R2DBC is expected to reduce platform-thread growth when concurrency is high and requests spend meaningful time waiting for MySQL. It does not guarantee higher RPS or lower latency. Results depend on query cost, pool size, transaction duration, database capacity, serialization, security lookups, external APIs, and client behavior.

Use the existing load harness at stages of 1,000, 1,500, 2,000, 2,500, and 3,000 requested RPS. Compare identical MVC/JDBC and WebFlux/R2DBC builds using:

- achieved RPS, failed requests, and dropped iterations;
- p50, p90, p95, and p99 latency;
- process CPU and CPU per successful request;
- RSS, JVM heap/non-heap, allocation rate, and GC pauses;
- total JVM threads and Reactor Netty event-loop utilization;
- R2DBC acquired, idle, pending, timeout, and acquisition latency metrics;
- MySQL active connections, CPU, I/O, query latency, lock waits, and deadlocks.

Warm both variants, use the same dataset and indexes, run at least three rounds per stage, and report variance. A health endpoint or empty-table test is not representative of application persistence.

## Failure And Overload Model

- Pool acquisition timeout should fail promptly rather than retain requests until client timeouts.
- Transaction rollback occurs on exceptions propagated through the suspend/reactive chain.
- Integration tests force a late question-write failure and verify that both the question and its stats row are absent after rollback.
- Cancellation should release subscriptions and connections; adapters must not swallow cancellation exceptions.
- Scheduler claims use atomic SQL and an expiring lease to prevent concurrent workers while permitting crash recovery.
- External event publication after commit can still be lost on process failure; use the transactional outbox where delivery is business-critical.

## Verification Checklist

```sh
cd backend
./gradlew test
./gradlew :tutor:bootJar
./gradlew :tutor:dependencies --configuration runtimeClasspath
```

Then verify:

- no production `JpaRepository`, `EntityManager`, `JdbcTemplate`, or Hibernate references;
- no request-path `.block()`, `runBlocking`, or JDBC data access;
- security session/device lookup suspends through R2DBC;
- mutation paths explicitly save changed relational entities;
- integration tests exercise MySQL-specific SQL using Testcontainers;
- Flyway can migrate the same schema before R2DBC startup;
- rollback, generated IDs, upserts, pagination, and concurrent claim behavior are covered.

## References

- [Spring Data R2DBC reference](https://docs.spring.io/spring-data/relational/reference/r2dbc.html)
- [Spring Framework R2DBC transaction support](https://docs.spring.io/spring-framework/reference/data-access/r2dbc.html)
- [Spring Data coroutine repositories](https://docs.spring.io/spring-data/relational/reference/kotlin/coroutines.html)
