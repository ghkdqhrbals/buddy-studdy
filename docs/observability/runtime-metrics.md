# Runtime Metrics In GraalVM Native Image

## Purpose

BuddyStudy emits one flat `runtime_metrics` JSON log every 30 seconds. Promtail
forwards the container log to Loki, and the server dashboard derives runtime,
database-pool, Reactor Netty, and host-resource charts from those samples.
The backend deployment also runs a port-free `buddystudy-db-metrics` observer.
It samples the PostgreSQL container with `docker stats` and reads
`pg_stat_activity` plus the live `max_connections` setting, then emits one
`database_runtime` JSON log every 30 seconds.

This keeps production monitoring compatible with the small-host PLG deployment:

```text
Backend Native Image
  -> structured runtime_metrics log
PostgreSQL + Docker Engine
  -> structured database_runtime log
Both
  -> Promtail
  -> Loki
  -> Grafana Server Dashboard
```

Prometheus and a JVM agent are intentionally not required on the backend host.

## Native Image Constraint

The production backend is a GraalVM Native Image, not a HotSpot JVM process.
`java.lang.management` APIs exist for compatibility, but individual MXBeans or
MXBean operations may be unsupported at runtime. A collector must therefore
assume that any JVM-oriented metric can be unavailable.

The collector follows these rules:

1. Host `/proc` counters, standard Micrometer binders, direct R2DBC pool
   metrics, and each MXBean family are collected independently.
2. One unsupported MXBean cannot discard an otherwise valid host or pool
   sample.
3. Partial samples set `runtimeMetricsDegraded=true`.
4. `runtimeMetricsUnavailable` contains a comma-separated list of unavailable
   collectors.
5. A failure that prevents the complete sample from being serialized emits
   `runtime_metrics_collection_failed` with the exception type and stack trace.
6. The dashboard labels the process as `native-image` or `jvm` and does not call
   unavailable Native Image counters JVM metrics.
7. The Loki payload is written as an explicit 52-field Jackson tree. It does
   not rely on reflection-based Kotlin data-class serialization, which keeps
   the reporter safe in a GraalVM Native Image.

## Metric Sources

| Area | Preferred source | Notes |
| --- | --- | --- |
| Process and system CPU | Micrometer gauges | Nullable when the runtime binder does not expose them |
| Host memory | `/proc/meminfo` | Linux container/host view |
| Process RSS | `/proc/self/status` | Includes managed and native memory |
| Disk | Java `FileStore` | Root filesystem capacity |
| Network | `/proc/net/dev` | Loopback is excluded |
| Open files | `/proc/self/fd` | Linux process descriptor count |
| Database pressure | Spring Boot `r2dbc.pool.*` Micrometer gauges | Acquired, allocated, idle, pending, configured limits |
| PostgreSQL connection ceiling | `current_setting('max_connections')` and `pg_stat_activity` | Actual server limit plus total and active sessions |
| PostgreSQL CPU | Docker Engine `stats` for `buddystudy-db` | Container CPU percentage; collected without Prometheus or an exposed metrics port |
| Event loop | Reactor Netty Micrometer gauges | Pending tasks, active connections, direct memory |
| Heap, threads, GC, classes | Micrometer JVM binders | Standard source across JVM and Native Image when available |
| Binder fallback | R2DBC `PoolMetrics` and `ManagementFactory` | Used per metric when the standard binder is absent |

The collector does not create a WebFlux-specific or hand-maintained metrics
model. It consumes the same Micrometer names published by Spring Boot,
Reactor Netty, the JVM binder, and the R2DBC pool. Direct pool/MXBean reads are
fallbacks so Native Image limitations do not erase the full sample.

Spring Boot Actuator automatically registers the standard JVM and system
Micrometer binders. Their values are sampled into the structured log rather
than exposing a public metrics endpoint or adding a Prometheus container to the
small production host.

Grafana does not collect PostgreSQL CPU or settings by itself. The lightweight
observer supplies those measurements through the existing log pipeline. It
mounts the Docker socket, runs no network listener, receives only the database
name and user, and never receives or logs the PostgreSQL password.

## Failure Diagnosis

When traffic and latency charts have data but runtime charts do not:

1. Search Loki for `runtime_metrics_collection_failed`.
2. If a sample exists, inspect `runtimeMetricsDegraded` and
   `runtimeMetricsUnavailable`.
3. Confirm the backend property
   `buddystudy.monitoring.runtime-metrics.enabled` is not disabled.
4. Confirm Promtail is forwarding the active backend container.
5. Treat missing MXBean-only values as a runtime capability issue, not as proof
   that CPU, memory, or threads are zero.

When R2DBC pool charts have data but PostgreSQL CPU or connection settings do
not:

1. Confirm `buddystudy-db-metrics` is running.
2. Search Loki for `{container="buddystudy-db-metrics"} |= "database_runtime "`.
3. Confirm the observer can reach the Docker socket and execute read-only
   `psql` queries inside `buddystudy-db`.

The server dashboard surfaces all three states:

- complete runtime sample;
- partial runtime sample with unavailable collectors;
- collection failure with a pointer to the backend stack trace.

A collection failure is considered recovered when a newer valid runtime sample
arrives. Historic failures in the selected range no longer keep the dashboard
in a failed state after recovery.

## Dashboard Time Range

`/system.html` provides the standard 15-minute, 1-hour, 6-hour, and 24-hour
ranges plus a `Custom` option. Custom From and To values include both calendar
date and local time. `Apply` fixes that explicit interval; `Refresh` reruns the
same interval instead of silently moving its end time.

## Verification

Run the backend collector tests:

```sh
cd backend
./gradlew --no-daemon :infra:test --tests '*RuntimeMetricsReporterTest'
```

Run the dashboard parser and query tests:

```sh
cd monitoring/api-dashboard
npm test
```

For production verification, deploy the backend and monitoring modules
separately, wait for at least two 30-second samples, and inspect
`https://monitoring.lowfidev.cloud/system.html` or the Server Runtime Grafana
dashboard. Runtime availability is a monitoring concern and must not be a
GitHub Actions runtime health gate.
