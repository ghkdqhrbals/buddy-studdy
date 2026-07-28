# BuddyStudy TestZone Operations

## Purpose

TestZone is a private self-service API performance workspace. The target
server is started independently. TestZone receives its base URL, executes a
saved k6 JavaScript file, stores artifacts, writes time-series metrics, and
links the run to Grafana.

It deliberately does not infer or display the target's server framework.

## Runtime Flow

1. The operator selects a project and opens a saved script.
2. The operator writes and saves the API request and response checks as a k6
   JavaScript file.
3. The TestZone API validates imports, HTTP/HTTPS URLs, duration, VUs, and
   target RPS.
4. The editor's `Run` action starts the bundled k6 binary with the saved file.
   Target URL, request options, VUs, RPS, and duration stay in that file.
5. k6 writes a summary, JSONL metric stream, and bounded log tail.
6. The service stores run metadata locally and imports one-second metric
   aggregates into InfluxDB.
7. Grafana filters the TestZone dashboard by the run's immutable `run_id`.
8. Deleting a run removes local artifacts and the matching InfluxDB series.

## Storage

| Data | Store | Retention |
| --- | --- | --- |
| Projects, script metadata, run metadata | atomic JSON under TestZone data | until deleted |
| JavaScript source files | TestZone data volume | until deleted |
| k6 summaries, JSONL, logs | per-run TestZone directories | until run deletion |
| k6 time series | InfluxDB `testzone` bucket | 30 days |
| Grafana dashboards | Git-provisioned JSON | source controlled |

InfluxDB is not the source of truth for scripts or run lifecycle. It is the
time-series analysis store.

The per-run k6 JSONL remains available for troubleshooting, while InfluxDB
receives only one-second API aggregates for request count, error ratio,
latency statistics, checks, iterations, dropped iterations, and virtual users.
The final run summary stores average, minimum, median, maximum, p90, p95, and
p99 latency. This keeps a 1,000-VU run from turning every request sample into
a long-lived time-series point.

## Deployment Modules

### TestZone Image

`.github/workflows/testzone-image.yml` runs service/dashboard static tests,
builds the ARM64 image on a GitHub-hosted runner, pushes it to GHCR, and can
dispatch the deploy repository.

### TestZone Runtime

`docs/deploy-repo-template/deploy-testzone.yml` runs on the MacBook Air
deploy-only runner. It pulls the image and starts:

- `buddystudy-testzone-service`
- `buddystudy-testzone-influxdb`

It does not build source code and does not use an HTTP health check as a
deployment gate.

### Monitoring UI

`docs/deploy-repo-template/deploy-macbookair-monitoring.yml` deploys:

- TestZone static UI and Nginx API proxy
- Grafana TestZone dashboard
- Grafana InfluxDB datasource

This separation prevents a dashboard-only change from restarting active test
infrastructure.

## Required Secrets

- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `OPENAI_USER_CONTENT_API_KEY`
- `OPENAI_SYSTEM_API_KEY` (must differ from `OPENAI_USER_CONTENT_API_KEY`)
- `API_DASHBOARD_BASIC_AUTH_HTPASSWD`
- `GRAFANA_ADMIN_PASSWORD`

The TestZone workflow creates the InfluxDB password, InfluxDB token, and
component password once under `MACBOOKAIR_TESTZONE_ROOT` using `umask 077`.
The monitoring workflow reads the same local InfluxDB token after TestZone has
been deployed.

Secret values must not be placed in scripts, Git, browser storage, run
metadata, or workflow summaries.

## Safety Limits

- maximum VUs: 1,000
- maximum target RPS: 3,000
- maximum duration: 60 minutes
- default simultaneous runs: 1
- maximum request body accepted by the TestZone API: 1 MB
- maximum JavaScript source: 250 KB
- remote JavaScript imports: prohibited
- target URL must use HTTP or HTTPS

Before testing any shared, third-party, or production-like endpoint, the
authenticated operator remains responsible for service ownership and traffic
approval.

## Script Contract

Every script exports `testConfig` with a name and absolute target URL, exports
bounded k6 `options`, includes a stable `api` tag, and validates both HTTP
status and required response fields. Credentials must not be committed or
stored in reusable scripts.

## Failure Handling

- A service restart marks queued/running runs as `interrupted`.
- A run can be cancelled before deletion.
- InfluxDB write failure marks the run failed rather than silently losing
  analysis data.
- OpenAI output is treated as an untrusted draft and passes the same script
  validator before it reaches the editor.
- Component operations use fixed Docker argument arrays and never accept an
  arbitrary image or command.
