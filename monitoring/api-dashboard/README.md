# BuddyStudy Monitoring Dashboard

The monitoring Nginx container serves the operational UI and proxies Loki,
Grafana, and the private TestZone API behind one Basic Auth boundary.

## Pages

- `/`: paginated API request logs and request/response/trace details
- `/performance.html`: endpoint latency and throughput grouped by API
- `/system.html`: application, database, Redis, host, and runtime metrics
- `/audit.html`: monitoring workspace page, authentication, and action history
- `/users.html`: authenticated member search, membership tiers, and quota controls
- `/streams.html`: authenticated Redis Stream search, cursor navigation,
  consumer lag, exact entry lookup, and redacted message details
- `/testzone.html`: live k6 script workspace, execution history, disposable
  test components, and Grafana links
- `/settings.html`: browser-local navigation and access-history preferences

`Users & Quotas`, `Redis Streams`, `Access & Audit`, and `Settings` are served
by the shared React Manage application. It provides one fixed navigation shell,
one session-scoped administrator API boundary, TanStack Query server state,
dense reusable tables, and a right-side object inspector. Redis field values
and outbox payload JSON can be explored as a nested tree or raw JSON without
flattening the stored object. The gradual migration is documented in
`docs/observability/MONITORING_REACT_MIGRATION.md`.

## Access Audit

The monitoring Nginx gateway records page views, denied Basic Auth attempts,
and mutating TestZone actions in a dedicated JSON access log. A local Promtail
instance forwards that file to Loki with only stable `job`, `service`, and
`event` labels. Client IP, authenticated username, path, user agent, status,
duration, and request ID remain JSON fields. Passwords, authorization values,
request bodies, and TestZone configuration values are not recorded.

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
