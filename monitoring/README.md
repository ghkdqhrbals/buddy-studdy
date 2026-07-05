# BuddyStudy Monitoring

This directory is the source of truth for the MacBook Air Grafana/Loki setup.

## Runtime Mounts

- Loki data: `~/buddystudy/monitoring/loki/data` -> `/loki`
- Loki config: `monitoring/loki/config/loki.yml` -> `/etc/loki/local-config.yaml`
- Grafana data: `~/buddystudy/monitoring/grafana/data` -> `/var/lib/grafana`
- Grafana provisioning: `monitoring/grafana/provisioning` -> `/etc/grafana/provisioning`
- Grafana dashboards: `monitoring/grafana/dashboards` -> `/var/lib/grafana/dashboards`

## Dashboards

- `monitoring/grafana/dashboards/buddystudy-logs.json`
  - Timeline graph uses the selected Grafana time range and `$__interval`.
  - Drag on the timeline to zoom into a time range.
  - Logs panel uses Grafana Logs infinite scrolling with newest logs first.

## External URLs

- Grafana: `https://grafana.lowfidev.cloud`
- Loki: `https://loki.lowfidev.cloud`
