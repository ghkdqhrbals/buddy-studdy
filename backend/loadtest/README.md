# Reusable MVC/JDBC vs WebFlux/R2DBC Load Test

This harness compares BuddyStudy's MVC/JDBC reference (`eca7e320`) with the
current WebFlux/R2DBC runtime under the same fixture, JVM-visible CPU count,
heap, PostgreSQL pool size, and request validation rules.

It deliberately uses two load models:

- **k6** generates open-loop constant arrival rates. It measures whether the
  server can keep up with a requested 1,000-3,000 RPS independently of response
  time.
- **nGrinder 3.5.9-p1** generates closed-loop VUser load. It measures sustained
  throughput, latency, failures, and recovery while each VUser waits for its
  previous response.

The design follows LINE's separation of generators from target servers and
central collection of results, plus Kakao's repeated, single-variable
comparison method:

- [LINE: parallel k6 executors](https://engineering.linecorp.com/en/blog/performance-test-in-jenkins-run-dynamic-pod-executors-in-kubernetes-parallelly)
- [LINE: nGrinder test automation](https://engineering.linecorp.com/ko/blog/server-side-test-automation-4/)
- [Kakao: performance-test experiment design](https://tech.kakao.com/posts/679)

## Scenarios

[`scenarios.json`](./scenarios.json) is the single source of truth used by both
tools.

| Scenario | Traffic |
| --- | --- |
| `health` | `GET /health` |
| `public-questions` | 20 public questions |
| `studies` | Authenticated studies with `limit=100` |
| `mobile-read-mix` | 70% public questions, 30% authenticated studies |

Every measured response is checked for its expected status, required JSON
paths, and non-empty fixture collections. Warm-up uses the same checks as a
strict fail-fast contract gate. The disposable fixture contains one user, 100
studies, and 500 graded public questions.

## Execution Interface

```bash
TOOL=k6|ngrinder|all
PROFILE=smoke|standard|diagnostic|soak
MVC_REF=eca7e320
WEBFLUX_REF=HEAD
SCENARIOS=health,public-questions,studies,mobile-read-mix
TARGET_HOST=http://<macbook-pro-private-address>:18080
LOAD_GENERATOR_SSH=<macbook-air-ssh-host>
GENERATOR_NETWORK_CAPACITY_MBPS=<measured-link-capacity>
backend/loadtest/run-comparison.sh
```

Use the MacBook Pro as the API/PostgreSQL/Redis target and the MacBook Air as
the generator:

```bash
TOOL=all PROFILE=standard \
TARGET_HOST=http://192.168.0.20:18080 \
LOAD_GENERATOR_SSH=gyuminhwangbo@gyumin-macbookair \
backend/loadtest/run-comparison.sh
```

The SSH account needs Docker, k6, Python 3, and rsync. The harness copies only
the load-test directory, transfers the access token through a mode-`0600`
temporary file, retrieves generated artifacts, and removes the remote working
directory on exit. It records both machine specifications and tool versions,
measures generator clock skew against the target, and rejects runs when the
absolute skew exceeds `MAX_CLOCK_SKEW_MS` (2,000 ms by default). Production
BuddyStudy hostnames are rejected.

Set `GENERATOR_NETWORK_CAPACITY_MBPS` to the measured usable link capacity when
network utilization should participate in automatic validity checks. A run is
invalidated at 95% of that value. Interface errors or dropped packets always
invalidate a run even when the capacity is not configured.

When `LOAD_GENERATOR_SSH` is omitted, both tools run locally. This is useful for
smoke validation but does not provide trustworthy absolute capacity because
the generator competes with the target.

### Profiles

| Profile | k6 | nGrinder | Diagnostics |
| --- | --- | --- | --- |
| `smoke` | 5 RPS, 5 seconds, one round | 1 VUser, 5 seconds | Off |
| `standard` | 1,000/1,500/2,000/2,500/3,000 RPS, 60 seconds, three rounds | 25/50/100/200/400 VUsers, 30-second ramp and 3-minute hold | Off |
| `diagnostic` | Selected saturation load, 60 seconds | Selected VUser load | JFR and NMT on |
| `soak` | 70% of `SUSTAINABLE_RPS`, 15 minutes | `SOAK_VUS`, 15 minutes | Off |

Standard rounds alternate runtime order. When k6 detects its first saturation
stage, `AUTO_FINE_SWEEP=true` fills the preceding region at 100 RPS intervals.

Primary throughput runs keep JFR and NMT disabled. Re-run the observed knee:

```bash
TOOL=k6 PROFILE=diagnostic SCENARIOS=studies \
TARGET_RPS_LIST=2200 backend/loadtest/run-comparison.sh
```

Run the 15-minute control after selecting the pre-saturation capacity:

```bash
TOOL=all PROFILE=soak SUSTAINABLE_RPS=2200 SOAK_VUS=140 \
backend/loadtest/run-comparison.sh
```

`-XX:ActiveProcessorCount=4` gives both JVMs the same visible processor count,
and both receive a fixed 512 MiB heap and DB pool 10. On macOS this is not a
hard host CPU quota; run on an otherwise idle target and compare target CPU
telemetry at equal throughput.

## Collected Metrics

- API: achieved and successful RPS, errors, dropped iterations, p50/p90/p95/p99
  for k6, and sustained TPS/mean latency for nGrinder.
- JVM: process CPU/RSS, heap, non-heap, direct memory, allocation, GC, OS and
  JVM threads.
- Runtime: Tomcat busy workers or Reactor Netty pending work.
- PostgreSQL: CPU, memory, connections, active/waiting queries, buffer cache
  hit rate.
- Redis: CPU, memory, clients, operations, misses, eviction, rejection.
- Generator: host and process CPU, memory use, RSS, transmitted and received
  bytes, average throughput, and interface errors/drops.

nGrinder's stable 3.5.9-p1 REST summary does not expose p90/p95/p99. Those
fields remain `null`; the report never fabricates percentiles.

## Artifacts

Each run creates:

- `raw/`: untouched k6 and nGrinder summaries
- `timeseries/`: one-second tool series
- `telemetry/`: target JVM, PostgreSQL, and Redis JSONL
- `generator-telemetry/`: load-generator JSONL
- `diagnostics/` and `jfr/`: diagnostic-profile artifacts
- `recovery/`: one-second `/health` samples collected during every cooldown
- `metadata.json`: refs, versions, machine, fixture, and resource limits
- `normalized-results.json` and `.jsonl`: common schema
- `REPORT.md`: repeated-run medians and verdict
- `DASHBOARD.html`: self-contained interactive comparison dashboard

The dashboard can filter by tool, scenario, round, and load. Its capacity chart
compares repeated load levels, while the selected-run chart reads the original
one-second k6 or nGrinder series rather than reconstructing points from
summaries.

## Validity And Verdict

- A run is invalid if generator CPU p95 exceeds 80%, memory use exceeds 95%,
  NIC errors/drops occur, configured network capacity reaches 95%, or mandatory
  generator telemetry is absent.
- k6 saturation starts when achieved throughput is under 95% of target, error
  rate exceeds 1%, dropped iterations occur, or PostgreSQL waiting queries are
  observed.
- The immediately preceding stage is sustainable only with error rate below
  0.1% and no dropped requests.
- A 5% successful-RPS decrease or 10% p95 increase is a regression only when
  all three standard rounds reproduce the direction.
- Differences under 5% are noise unless all three rounds agree.
- The final winner is stated only when open-loop k6 and closed-loop nGrinder
  agree. Otherwise the report explicitly leaves the result inconclusive.

## nGrinder Lifecycle And Security

The controller and one agent are pinned to `3.5.9-p1`, bound to
`127.0.0.1`, and removed after the test. They are never exposed as a permanent
service. This is intentional because the
[upstream repository](https://github.com/naver/ngrinder) was archived in
September 2025. See the [official releases](https://github.com/naver/ngrinder/releases).

The published nGrinder images are AMD64-only. On Apple Silicon, the harness
extracts the pinned, architecture-independent `3.5.9-p1` controller WAR and
builds temporary ARM Java controller/agent wrappers locally. Test data lives in
named Docker volumes, and the controller repository containing each generated
script and its short-lived token is deleted by `stack.sh down` after the run.

## Static Verification

CI does not start the backend, call health endpoints, or run load. It checks
only scenario/schema consistency, Python and shell syntax, Docker Compose
rendering, and deterministic normalization tests:

```bash
backend/loadtest/static-check.sh
```

All real load runs use disposable local PostgreSQL and Redis containers. Never
point this harness at production.
