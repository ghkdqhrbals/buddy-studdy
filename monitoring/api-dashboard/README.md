# BuddyStudy Monitoring Dashboard

This static dashboard is served by the monitoring-only Nginx container and
queries Loki through the authenticated reverse proxy.

## Pages

- `/`: paginated API request logs and request/response/trace details
- `/performance.html`: endpoint latency and throughput grouped by API
- `/system.html`: application, JVM/native-image, database, Redis, host, disk,
  network, thread, and runtime queue metrics
- `/testzone.html`: performance-test projects, execution history, capacity,
  latency, timeout, and resource evidence

TestZone does not execute load from the browser. The New run dialog only
prepares a reviewed command. This keeps the monitoring origin unable to launch
arbitrary processes.

## Refresh TestZone Data

```bash
python3 backend/loadtest/export_testzone.py \
  --output monitoring/api-dashboard/public/testzone-data.json
```

The catalog reads the result directories listed in
`backend/loadtest/testzone-projects.json`. Add a completed result directory to
that file and regenerate the catalog before the monitoring deployment.

Historical k6 results collected before `successful_request_duration` was
introduced contain only `http_req_duration`. TestZone labels this value
**all-request p95**, because it includes failed requests and client timeouts.
It shows successful-only p95 as `not collected` instead of deriving an
unsupported value.

## Verification

```bash
npm test
node --check public/testzone.js
node --check public/testzone-model.js
```

Deployment is owned by
`docs/deploy-repo-template/deploy-macbookair-monitoring.yml`. Backend and admin
deploy workflows must not restart or recreate this dashboard.
