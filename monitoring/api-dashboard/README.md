# BuddyStudy Monitoring Dashboard

The monitoring Nginx container serves the operational UI and proxies Loki,
Grafana, and the private TestZone API behind the backend administrator session.

## Pages

- `/`: paginated API request logs and unmasked request/response/trace details
- `/performance.html`: endpoint latency and throughput grouped by API
- `/system.html`: application, database, Redis, host, and runtime metrics
- `/audit.html`: monitoring workspace page, authentication, and action history
- `/users.html`: authenticated member search, membership tiers, and quota controls
- `/jobs.html`: independently server-paginated managed-job status and execution
  history, run details, and authenticated retries
- `/advertising.html`: Coupang advertising campaign creation and editing,
  localized creative, audience and frequency controls, performance totals, and
  the live server-ranking policy used to mix ads into public questions
- `/streams.html`: authenticated Redis Stream delivery status with
  configured MAXLEN and retention use, consumer-group offsets, lag, pending
  ranges, per-consumer ownership, retry counts, partial-inspection errors,
  cursor navigation, exact entry lookup, and redacted message details
- `/testzone.html`: live k6 script workspace, execution history, disposable
  test components, and Grafana links
- `/settings.html`: browser-local navigation and access-history preferences

Every monitoring route is served by the shared React application. It provides
one fixed navigation shell and visual system across API Logs, API Performance,
TestZone, Users & Quotas, Advertising, Batch Jobs, Redis Streams, Access & Audit, and Settings. Manage
adds one session-scoped administrator API boundary, TanStack Query server
state, dense reusable tables, and a right-side object inspector. Redis field
values and outbox payload JSON can be explored as a nested tree or raw JSON
without flattening the stored object. The migration and controller boundary are documented in
`docs/observability/MONITORING_REACT_MIGRATION.md`.

API exchange logs are intentionally rendered exactly as captured by the
backend, including authorization, client-secret, cookie, token, password, and
other credential fields in request/response headers and bodies. Existing body
capture limits and MCP body suppression still apply. The administrator session
boundary protects this raw view; Slack/Codex search output and incident
dispatches apply their own redaction before data leaves the monitoring system,
and API exchange log events and breadcrumbs are excluded from Sentry.

## Access Audit

The monitoring Nginx gateway records page views, denied administrator-session
requests, and mutating TestZone actions in a dedicated JSON access log. It also
writes gateway warnings and errors to a separate file. A local Promtail
instance tails the active files into Loki with only stable `job`, `service`,
and `event` labels. Client IP, authenticated username, path, user agent,
status, duration, and request ID remain JSON fields. Passwords, authorization
values, request bodies, and TestZone configuration values are not recorded.

The host files are delivery spools, not the long-term archive. The access log
rotates at 8 MiB and the error log at 2 MiB, with three numeric archives for
each. The isolated rotator shares only the Nginx PID namespace and log mount;
after a rename it sends `USR1` so Nginx reopens the active path. Promtail stores
its offsets on a persistent mount and keeps reading the renamed inode while it
runs. The rotator waits 60 seconds after startup so Promtail can attach before
the first rename. The scrape path intentionally does not match numeric archives,
because doing so would ingest the same file again after each rename. Rotation
is checked every 30 seconds after that grace period, so a write burst can
temporarily exceed those sizes.
If Promtail restarts while it still has unread data in a renamed file, or Loki
is unavailable long enough for a fourth rotation, those unshipped audit/error
lines can be lost. Loki retains successfully shipped logs for seven days.

Container stdout/stderr uses Docker's bounded `local` log driver (10 MiB times
three files, compressed) independently of these Nginx spools. See the main
[monitoring storage policy](../README.md#disk-retention-and-data-loss-boundaries)
for all limits and trade-offs.

## TestZone Behavior

TestZone is runtime-neutral. It does not know or display whether the target is
MVC, WebFlux, Tomcat, or Netty. A project contains a name, scripts, and
executions. Each execution captures its own target URL, so one project can test
multiple deployments without rewriting project settings.

The browser can:

- create, edit, validate, and delete k6 JavaScript files
- start, cancel, and delete executions
- compare per-second HTTP success/error counts, average/p90/p95 latency, and
  RPS/average latency/error count
- preserve every k6 `options.scenarios` entry in run metadata, summarize the
  target RPS and VUser capacity in history, and switch run-detail
  KPIs and time-series between all scenarios and one scenario
- inspect TPS, mean time to first byte (MTTFB), mean total HTTP time (MTT),
  HTTP success/error counts, and average/minimum/median/maximum/p90/p95 latency
- deploy, restart, and delete fixed-catalog MySQL and Redis test
  components
- add validated key-value environment settings to test components and inspect
  container CPU/memory plus MySQL connection/cache and Redis
  client/throughput/cache metrics
- open an execution in Grafana using its `run_id`

Tests can be started only with `Run` in the saved script editor. The Overview
page is read-only and does not expose a separate execution action. The target
URL comes from `export const testConfig`; headers, request bodies, duration,
VUs, arrival rate, and every other execution setting live in the JavaScript
file. The browser never receives the InfluxDB token.

New projects intentionally start without scripts. In the Scripts tab, `+`
opens a blank unsaved file directly in the editor. The file is created and
validated by the TestZone API only when the user selects `Save`; existing
script contents are never copied into a new file implicitly. Failed saves keep
the unsaved indicator and show actionable diagnostics directly above the
editor; the indicator clears only after the API confirms a successful save.

Limits are enforced by the TestZone API:

- 1 active execution by default
- 1,000 VUs maximum
- 3,000 target RPS maximum
- 60 minutes maximum duration
- valid absolute HTTP or HTTPS target URL
- no remote JavaScript imports

The old `testzone-data.json` exporter is retained only for historical result
archives. The live page does not read it.

Metric semantics and component collection behavior are documented in
`docs/observability/testzone-load-metrics.md`.

## Verification

```bash
(cd monitoring/api-dashboard && npm ci && npm test)
(cd monitoring/testzone-service && npm test)
node --check monitoring/api-dashboard/public/testzone.js
node --check monitoring/testzone-service/src/server.mjs
```

Monitoring UI deployment is owned by
`docs/deploy-repo-template/deploy-macbookair-monitoring.yml`. The execution
service and InfluxDB are owned by
`docs/deploy-repo-template/deploy-testzone.yml`.

`npm test` builds the React bundle into `public/react` before running the
contract suite. The generated `manage.js` and `manage.css` are committed
because the monitoring deployment copies the versioned `public` artifact
without compiling on the deploy host.
