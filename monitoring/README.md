# BuddyStudy Monitoring

This directory is the source of truth for the MacBook Air Grafana/Loki setup.

## Runtime Mounts

- Loki data: `~/buddystudy/monitoring/loki/data` -> `/loki`
- Loki config: `monitoring/loki/config/loki.yml` -> `/etc/loki/local-config.yaml`
- Grafana data: `~/buddystudy/monitoring/grafana/data` -> `/var/lib/grafana`
- Grafana provisioning: `monitoring/grafana/provisioning` -> `/etc/grafana/provisioning`
- Grafana dashboards: `monitoring/grafana/dashboards` -> `/var/lib/grafana/dashboards`
- TestZone state/scripts/runs: `~/buddystudy/monitoring/testzone/data` -> `/data`
- TestZone InfluxDB: `~/buddystudy/monitoring/testzone/influxdb` -> `/var/lib/influxdb2`

## Access Control

- `api-dashboard` uses the backend administrator session for both monitoring
  and Manage pages. An unauthenticated browser is redirected to
  `/login.html`, then returned to its original page after sign-in.
- Administrator accounts are stored as BCrypt password hashes in the backend
  database and managed under `Manage > Administrators`. The configured
  `ADMIN_USERNAME` and `ADMIN_PASSWORD` account is imported on its first
  successful login so existing deployments keep access during migration.
- Nginx validates the same bearer session before proxying Loki and TestZone
  requests. There is no separate dashboard Basic Auth credential.

- Loki and both public gateway ports are bound to `127.0.0.1` only.
  Routingflare exposes the authenticated monitoring gateway and the Grafana
  gateway on separate hostnames and local ports. Grafana itself remains
  private on the monitoring Docker network.
- TestZone has no public container port. Its API is reachable only through the
  authenticated dashboard Nginx route.
- Operators manage update campaigns and maintenance under `Manage > App
  Control`. The `App updates` tab publishes recommended or required iOS
  campaigns and exposes per-device version/conversion progress. The
  `Maintenance` tab starts, schedules, and ends full-screen maintenance
  windows. The dashboard calls the authenticated backend admin API; the backend
  stores the audit history and publishes the active policy to Firebase Remote
  Config. Monitoring does not run a separate service-status server or expose a
  public status API.
- Saved k6 scripts may target any valid HTTP or HTTPS URL. The authenticated
  operator is responsible for testing only systems they are authorized to load.
- Disposable components are selected from a fixed server-side catalog. The
  browser cannot submit Docker images or commands.

## Dashboards

- `https://monitoring.lowfidev.cloud`
  - Custom API log dashboard served by `monitoring/api-dashboard`.
  - API rows expand inline to show request, response, optional stack trace, and related logs.
  - `/performance.html` shows p50, p90, p95, and p99 latency grouped by API endpoint.
  - `/system.html` is the server dashboard organized around the four Golden Signals:
    - Traffic: RPS time series.
    - Latency: p50, p95, and p99 time series derived by Loki.
    - Errors: 4xx and 5xx request ratios.
    - Saturation: process/system CPU, host/runtime/direct memory, disk, network, best-effort runtime threads and GC, R2DBC pool usage and pending acquires, and Reactor Netty event-loop pending tasks and active connections.
  - The server dashboard queries aggregated Loki metric series instead of downloading all API logs into the browser.
  - Runtime metric snapshots are emitted every 30 seconds by the backend and retained according to Loki retention.
  - Production runs as a GraalVM Native Image. Runtime collectors are isolated so an unsupported MXBean produces a partial sample instead of dropping every host and pool metric. See `docs/observability/runtime-metrics.md`.
  - Timestamps are rendered in KST with millisecond precision.
- `https://grafana.lowfidev.cloud`
  - Grafana login and provisioned BuddyStudy dashboards.
  - The monitoring deployment synchronizes the persisted `admin` account with
    the `GRAFANA_ADMIN_PASSWORD` deployment secret on every rollout.
  - Anonymous access is disabled; unauthenticated users see the login screen
    instead of a protected default dashboard.
  - Backend ERROR alerts show a compact `오류 로그 보기` hyperlink
    in Slack instead of printing the raw Explore URL. API links use the captured
    `requestId`, while background links use the original timestamp and logger;
    both ranges start at the event time instead of a moving `now-15m` window.
  - Legacy custom-dashboard paths redirect to
    `https://monitoring.lowfidev.cloud` so old bookmarks cannot send Loki
    requests to Grafana.
- Slack/Codex log investigations use `monitoring/api-dashboard/scripts/codex-log-search.mjs`.
  - See `docs/observability/slack-codex-log-search.md`.
- `monitoring/grafana/dashboards/buddystudy-logs.json`
  - Timeline graph uses the selected Grafana time range and `$__interval`.
  - Drag on the timeline to zoom into a time range.
  - Logs panel uses Grafana Logs infinite scrolling with newest logs first.
- `monitoring/grafana/dashboards/buddystudy-testzone.json`
  - Reads k6 request rate, p95 latency, error rate, and VUs from InfluxDB.
  - Filters every panel by TestZone `run_id`.
  - TestZone run deletion also deletes the matching InfluxDB series.

## TestZone

TestZone consists of two independently deployed modules:

1. The monitoring workflow deploys the browser UI, Nginx proxy, Grafana
   dashboard, and InfluxDB datasource definition.
2. The TestZone workflow deploys the k6 execution service and InfluxDB.

Target URL, request settings, duration, VUs, and target RPS are owned by each
saved k6 script. Tests run only from the script editor.

See [TestZone Operations](../docs/performance/TESTZONE_OPERATIONS.md).

## External URLs

- API Logs: `https://monitoring.lowfidev.cloud`
- Grafana: `https://grafana.lowfidev.cloud`
- TestZone: `https://monitoring.lowfidev.cloud/testzone.html`
