# BuddyStudy Monitoring Dashboard

The monitoring Nginx container serves the operational UI and proxies Loki,
Grafana, and the private TestZone API behind one Basic Auth boundary.

## Pages

- `/`: paginated API request logs and request/response/trace details
- `/performance.html`: endpoint latency and throughput grouped by API
- `/system.html`: application, database, Redis, host, and runtime metrics
- `/testzone.html`: live k6 script workspace, execution history, disposable
  test components, and Grafana links

## TestZone Behavior

TestZone is runtime-neutral. It does not know or display whether the target is
MVC, WebFlux, Tomcat, or Netty. A project contains a name, scripts, and
executions. Each execution captures its own target URL, so one project can test
multiple deployments without rewriting project settings.

The browser can:

- create, edit, validate, and delete k6 JavaScript files
- start, cancel, and delete executions
- deploy, restart, and delete fixed-catalog PostgreSQL and Redis test
  components
- open an execution in Grafana using its `run_id`

Tests can be started only with `Run` in the saved script editor. The Overview
page is read-only and does not expose a separate execution action. The target
URL comes from `export const testConfig`; headers, request bodies, duration,
VUs, arrival rate, and every other execution setting live in the JavaScript
file. The browser never receives the InfluxDB token.

New projects intentionally start without scripts. In the Scripts tab, `+`
opens a blank unsaved file directly in the editor. The file is created and
validated by the TestZone API only when the user selects `Save`; existing
script contents are never copied into a new file implicitly.

Limits are enforced by the TestZone API:

- 1 active execution by default
- 1,000 VUs maximum
- 3,000 target RPS maximum
- 60 minutes maximum duration
- valid absolute HTTP or HTTPS target URL
- no remote JavaScript imports

The old `testzone-data.json` exporter is retained only for historical result
archives. The live page does not read it.

## Verification

```bash
(cd monitoring/api-dashboard && npm test)
(cd monitoring/testzone-service && npm test)
node --check monitoring/api-dashboard/public/testzone.js
node --check monitoring/testzone-service/src/server.mjs
```

Monitoring UI deployment is owned by
`docs/deploy-repo-template/deploy-macbookair-monitoring.yml`. The execution
service and InfluxDB are owned by
`docs/deploy-repo-template/deploy-testzone.yml`.
