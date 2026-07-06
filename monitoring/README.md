# BuddyStudy Monitoring

This directory is the source of truth for the MacBook Air Grafana/Loki setup.

## Runtime Mounts

- Loki data: `~/buddystudy/monitoring/loki/data` -> `/loki`
- Loki config: `monitoring/loki/config/loki.yml` -> `/etc/loki/local-config.yaml`
- Grafana data: `~/buddystudy/monitoring/grafana/data` -> `/var/lib/grafana`
- Grafana provisioning: `monitoring/grafana/provisioning` -> `/etc/grafana/provisioning`
- Grafana dashboards: `monitoring/grafana/dashboards` -> `/var/lib/grafana/dashboards`

## Access Control

- `api-dashboard` is protected with nginx Basic Auth.
- Set `API_DASHBOARD_BASIC_AUTH_HTPASSWD` to a full htpasswd line before starting the stack.
- Generate the value with:

```sh
docker run --rm httpd:2.4-alpine htpasswd -nbB admin 'your-password'
```

- Loki and Grafana container ports are bound to `127.0.0.1` only. External access should go through the API dashboard reverse proxy.

## Dashboards

- `https://grafana.lowfidev.cloud`
  - Custom API log dashboard served by `monitoring/api-dashboard`.
  - API rows expand inline to show request, response, optional stack trace, and related logs.
  - `/performance.html` shows p50, p90, p95, and p99 latency grouped by API endpoint.
  - Timestamps are rendered in KST with millisecond precision.
- `monitoring/grafana/dashboards/buddystudy-logs.json`
  - Timeline graph uses the selected Grafana time range and `$__interval`.
  - Drag on the timeline to zoom into a time range.
  - Logs panel uses Grafana Logs infinite scrolling with newest logs first.

## External URLs

- API Logs: `https://grafana.lowfidev.cloud`
- Grafana: `https://grafana.lowfidev.cloud/grafana/`
