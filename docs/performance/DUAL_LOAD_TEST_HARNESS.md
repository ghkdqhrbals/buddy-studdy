# BuddyStudy Dual Load-Test Method

## Purpose

The benchmark answers two different questions without conflating them:

1. **Open-loop capacity:** At a requested arrival rate, can the server complete
   at least 95% of work without drops or errors?
2. **Closed-loop sustainability:** With a fixed population of active clients,
   what throughput and latency can the server sustain and recover from?

k6 answers the first question. nGrinder answers the second. A framework
conclusion requires both tools to reproduce the same direction.

## Controlled Variables

| Variable | Value |
| --- | --- |
| MVC baseline | Git ref `eca7e320` |
| Reactive candidate | Explicit WebFlux ref, normally `HEAD` |
| JVM heap | 512 MiB fixed |
| JVM visible processors | 4 |
| MySQL pool | 10 |
| Fixture | 1 user, 100 studies, 500 public questions |
| Logging | API exchange logger off for primary capacity |
| Background jobs | Disabled |
| Runtime order | Alternated by round |
| Standard repeats | 3 |

Only the runtime implementation changes. The scenario manifest, data, resource
configuration, response assertions, target host, generator host, and durations
remain fixed.

## Measurement Sequence

1. Record both machine specifications and tool versions; reject generator
   clock skew over two seconds or insufficient target disk.
2. Build both Git refs.
3. Start disposable MySQL and Redis and seed the fixed fixture.
4. Run body-validating smoke checks.
5. Run three alternating k6 rounds from 1,000 through 3,000 RPS for each
   isolated API: public questions, then authenticated studies.
6. Re-run the first saturation region at 100 RPS increments.
7. Run three alternating nGrinder rounds for each isolated API at 25, 50, 100,
   200, 400, 600, 800, and 1,000 simultaneous VUsers.
8. Re-run the knee with JFR/NMT.
9. Run 15 minutes at approximately 70% of sustainable capacity.
10. Normalize raw results, invalidate generator-limited samples, and render the
    report and dashboard.

`/health` is not a load-test scenario. The harness only uses it internally to
confirm local candidate startup and cooldown recovery, and excludes those
samples from API throughput and framework verdicts. The optional
`mobile-read-mix` workload is secondary evidence after the isolated endpoint
runs; it does not replace them.

The generated dashboard separates the load-level capacity curve from original
per-second samples. This keeps a median across rounds from being mistaken for
a time series and makes transient throughput or latency collapse visible.

## Why Two Machines

The load generator must not steal CPU, memory bandwidth, or sockets from the
API and database. The MacBook Air runs k6 or the temporary nGrinder stack. The
MacBook Pro runs both candidate APIs, MySQL, and Redis. Generator
telemetry is a validity gate rather than an informational afterthought.
The metadata records the generator's CPU count, memory, disk headroom,
architecture, k6/Docker versions, round-trip time, and measured clock skew so a
later run can reproduce or invalidate the environment.
Interface errors and dropped packets invalidate a run. When
`GENERATOR_NETWORK_CAPACITY_MBPS` is provided, average utilization above 95%
also invalidates it.

Each API's 1,000-VUser stage uses one isolated nGrinder agent with four worker
processes and 250 threads per process. This preserves an exact 1,000-client
population while avoiding a single 1,000-thread worker process. Host CPU p95
above 80%, memory use above 95%, or NIC errors/drops still invalidate the
sample: a generator-limited result is not reported as API saturation.

## Interpretation

Completed-request latency can look deceptively good after a server begins
dropping work. Therefore saturation is derived from target achievement, error
rate, drops, and DB waits before latency is interpreted. Resources are compared
at the same successful RPS, not only at each runtime's maximum.

JFR and NMT are diagnosis tools, not primary measurement tools. They are
enabled only after the saturation knee is known to avoid profiler
perturbation.

Process CPU follows the host `ps` convention: 100% is one fully occupied core.
The normalized result also records p95 as consumed cores so multi-core values
are not mistaken for whole-host utilization.

## Industry References

- [LINE k6 parallel executors](https://engineering.linecorp.com/en/blog/performance-test-in-jenkins-run-dynamic-pod-executors-in-kubernetes-parallelly)
- [LINE nGrinder automation](https://engineering.linecorp.com/ko/blog/server-side-test-automation-4/)
- [Kakao experiment-oriented performance testing](https://tech.kakao.com/posts/679)
- [nGrinder repository](https://github.com/naver/ngrinder)
- [nGrinder releases](https://github.com/naver/ngrinder/releases)
