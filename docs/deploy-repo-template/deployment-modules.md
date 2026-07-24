# BuddyStudy Deployment Modules

Deployments are split by runtime ownership. Do not combine unrelated modules in
one workflow run just because they share a host.

## Modules

| Module | Workflow | Trigger | Runner | Owns |
| --- | --- | --- | --- | --- |
| Backend API | `Deploy BuddyStudy Backend` | `backend-image-published`, manual | EC2 self-hosted | Backend app rollout, backend env, backend nginx route |
| Admin frontend | `Deploy BuddyStudy Admin Frontend` | `admin-frontend-image-published`, manual | EC2 self-hosted | Admin frontend container only |
| Monitoring receiver | `Deploy BuddyStudy Monitoring on MacBook Air` | manual | MacBook Air self-hosted | API Logs, API Performance, Server Dashboard, TestZone UI, Grafana, Loki, monitoring auth |
| Monitoring routing | `Deploy BuddyStudy Monitoring Routes on MacBook Air` | manual | MacBook Air self-hosted | Routingflare routes for the monitoring UI and Grafana |
| TestZone execution | `Deploy BuddyStudy TestZone on MacBook Air` | `testzone-image-published`, manual | MacBook Air self-hosted | k6 runner, script/project/run storage, InfluxDB, approved disposable test components |
| Health monitor | Cloudflare Worker workflow | manual or source workflow | GitHub-hosted | Cloudflare Cron readiness checks and Slack alerts |

## Rules

- A workflow must deploy one module. If two modules need to change, run two
  workflows.
- A job must have a module-specific name such as `deploy_backend`,
  `deploy_admin_frontend`, or `deploy_monitoring`.
- Backend image build remains in the app repository on GitHub-hosted runners.
- EC2 self-hosted runners are deploy-only. They pull images and restart
  containers, but must not compile backend code or build Docker images.
- Monitoring dashboards, the TestZone browser UI, Loki, and Grafana are
  deployed by the monitoring workflow. TestZone's execution service and
  InfluxDB are deployed by the TestZone workflow. Backend deploys must not
  recreate any of them.
- Runtime health checks are not GitHub Actions deploy gates. GitHub Actions may
  validate deploy mechanics such as image pull, container process survival, and
  nginx syntax only.
- Shared infrastructure changes, such as nginx routing needed by multiple
  modules, must be called out in the workflow summary and kept backwards
  compatible with currently running containers.

## Change Routing

- Backend Kotlin/API/env changes: build backend image, then run backend deploy.
- Backend runtime secrets are read by the backend deploy workflow from AWS
  Secrets Manager. Required values such as `OPENAI_API_KEY` must be validated
  before writing the container env file so an optional Spring config import
  cannot silently start a partially configured backend.
- PostgreSQL credentials and connection URLs are owned by the
  `buddystudy/prod/postgres` secret. It contains `dbname`, `username`,
  `password`, `jdbcUrl`, and `r2dbcUrl`; the deploy workflow reads both JDBC
  and R2DBC settings from that secret. A legacy host password file is migrated
  into the secret once and is not the continuing configuration source.
- Admin frontend UI changes: build admin frontend image, then run admin frontend
  deploy.
- Grafana/Loki/API Logs/TestZone UI changes: run the monitoring deploy.
- TestZone runner, InfluxDB integration, k6 validation, or component catalog
  changes: build `buddystudy-testzone`, then run the TestZone deploy.
  The deploy receives `OPENAI_API_KEY` as a GitHub Actions secret and owns
  persistent local InfluxDB/component credentials under
  `MACBOOKAIR_TESTZONE_ROOT`.
- Monitoring hostname or port changes: run the monitoring routing workflow
  after the monitoring deploy. Routingflare maps
  `monitoring.lowfidev.cloud` to the dashboard and
  `grafana.lowfidev.cloud` to the standalone Grafana origin.
  The routing job also provisions both Cloudflare Tunnel CNAME records,
  enables Routingflare autostart, and restarts the named tunnel after route
  changes.
- Cloudflare Health Monitor changes: deploy the Cloudflare Worker only.
- Portfolio runtime or hostname changes: run
  `portfolio-site/scripts/setup-routingflare.sh` on the owning Mac. The
  production process is supervised by `launchd`, and Routingflare maps
  `buddystudy.lowfidev.cloud` to the local origin. This operation must not
  deploy the backend, monitoring stack, or admin frontend.
- Nginx public routing changes: update the owning module workflow template and
  state which module is responsible for reloading nginx.
