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
- Service status state: `~/buddystudy/monitoring/status/data` -> `/data`

## Access Control

- `api-dashboard` is protected with nginx Basic Auth.
- Set `API_DASHBOARD_BASIC_AUTH_HTPASSWD` to a full htpasswd line before starting the stack.
- Generate the value with:

```sh
docker run --rm httpd:2.4-alpine htpasswd -nbB admin 'your-password'
```

- Loki and both public gateway ports are bound to `127.0.0.1` only.
  Routingflare exposes the authenticated monitoring gateway and the Grafana
  gateway on separate hostnames and local ports. Grafana itself remains
  private on the monitoring Docker network.
- TestZone has no public container port. Its API is reachable only through the
  authenticated dashboard Nginx route.
- Service maintenance is owned by monitoring, not the backend database.
  Operators manage it under `Manage > Service status`. The only public route is
  the read-only `GET /status/api/v1/service-status`; mutation routes retain
  monitoring Basic Auth and are included in the access audit log.
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
- Service status: `https://monitoring.lowfidev.cloud/status/api/v1/service-status`
