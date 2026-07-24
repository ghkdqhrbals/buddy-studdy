# BuddyStudy Monitoring Dashboard

The monitoring Nginx container serves the operational UI and proxies Loki,
Grafana, and the private TestZone API behind one Basic Auth boundary.

## Pages

- `/`: paginated API request logs and request/response/trace details
- `/performance.html`: endpoint latency and throughput grouped by API
- `/system.html`: application, database, Redis, host, and runtime metrics
- `/testzone.html`: live k6 script workspace, execution history, disposable
  test components, OpenAI assistance, and Grafana links

## TestZone Behavior

TestZone is runtime-neutral. It does not know or display whether the target is
MVC, WebFlux, Tomcat, or Netty. A project contains a name, scripts, and
executions. Each execution captures its own target URL, so one project can test
multiple deployments without rewriting project settings.

The browser can:

- create, edit, validate, and delete k6 JavaScript files
- choose a target URL for every execution
- configure headers and environment values without embedding credentials in
  source files
- start, cancel, and delete executions
- deploy, restart, and delete allowlisted PostgreSQL, Redis, and Kafka test
  components
- open an execution in Grafana using its `run_id`

`New run` calls the TestZone service and starts k6. It does not copy a shell
command. Sensitive header and environment names are redacted from persisted
metadata. The browser never receives the OpenAI API key or InfluxDB token.

Limits are enforced by the TestZone API:

- 1 active execution by default
- 1,000 VUs maximum
- 3,000 target RPS maximum
- 60 minutes maximum duration
- target hostname allowlist
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
