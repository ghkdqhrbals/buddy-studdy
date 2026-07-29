# BuddyStudy Deployment Modules

Deployments are split by runtime ownership. Do not combine unrelated modules in
one workflow run just because they share a host.

## Modules

| Module | Workflow | Trigger | Runner | Owns |
| --- | --- | --- | --- | --- |
| Backend API | `Deploy BuddyStudy Backend` | `backend-image-published`, manual | EC2 self-hosted | Backend app rollout, backend env, backend nginx route, log-only MySQL runtime observer, backend-log multiline collection |
| Backend network | `Configure BuddyStudy Backend Network` | manual | EC2 self-hosted | Redis administrator ingress on the backend security group |
| Database cutover | `Migrate BuddyStudy PostgreSQL To MySQL` | manual, one-time | EC2 self-hosted | PostgreSQL backup, MySQL import, row-count and reference validation, automatic pre-cutover rollback |
| Admin frontend | `Deploy BuddyStudy Admin Frontend` | `admin-frontend-image-published`, manual | EC2 self-hosted | Admin frontend container only |
| iOS TestFlight | `Release iOS App` | `v*`, manual | GitHub-hosted macOS | Release planning, signed IPA build, artifact retention, and TestFlight upload as separate jobs |
| Monitoring receiver | `Deploy BuddyStudy Monitoring on MacBook Air` | manual | MacBook Air self-hosted | API Logs, API Performance, TestZone UI, Grafana, Loki, ERROR-log Slack alerting, monitoring auth and access audit, customer-facing service status and maintenance history |
| Monitoring routing | `Deploy BuddyStudy Monitoring Routes on MacBook Air` | manual | MacBook Air self-hosted | Routingflare routes for the monitoring UI and Grafana |
| TestZone execution | `Deploy BuddyStudy TestZone on MacBook Air` | `testzone-image-published`, manual | MacBook Air self-hosted | k6 runner, script/project/run storage, InfluxDB, approved disposable test components |
| Health monitor | Cloudflare Worker workflow | manual or source workflow | GitHub-hosted | Explicit diagnostic endpoint only; production scheduled checks are disabled |

Explicit release tags provide a CLI-independent deployment entry point:

- `deploy/backend-*` builds and deploys the backend module.
- `deploy/testzone-*` builds and deploys the TestZone execution module.
- `deploy/monitoring-*` dispatches the monitoring receiver deployment.

The tags are intentional release commands. Ordinary branch pushes do not deploy
runtime modules. Backend, monitoring, and TestZone source workflows wait for the matching
private deploy workflow and fail when that deploy does not complete
successfully; a successful dispatch alone is not reported as a successful
deployment.

## Rules

- A workflow must deploy one module. If two modules need to change, run two
  workflows.
- A job must have a module-specific name such as `deploy_backend`,
  `deploy_admin_frontend`, or `deploy_monitoring`.
- The iOS release workflow remains an iOS-only module. It separates release
  planning, the signed IPA build, TestFlight upload, and completion reporting
  into dependent jobs so each failure boundary is visible without combining
  backend, monitoring, or admin deployment.
- Backend image build remains in the app repository on GitHub-hosted runners.
- Backend images support `native` and `jvm` runtime modes from one Dockerfile.
  Native remains the default for tag-triggered releases. Manual image builds
  select the runtime explicitly, stamp it into
  `io.buddystudy.backend.runtime`, and dispatch that value with the immutable
  runtime-qualified image reference (`<tag>-native` or `<tag>-jvm`). The
  unqualified tag remains a compatibility alias. The deploy workflow verifies
  the label before rollout, so changing runtime does not change the backend
  environment or routing contract.
- Production may explicitly select the JVM runtime without changing the API or
  routing contract. The backend image workflow must receive
  `backend_runtime=jvm`; the deploy workflow verifies that runtime label before
  replacing the container.
- EC2 self-hosted runners are deploy-only. They pull images and restart
  containers, but must not compile backend code or build Docker images.
- Before pulling a backend release, the backend deploy removes only Docker
  images that are not referenced by any container. This keeps the small EC2
  disk from accumulating superseded release images without touching running
  or rollback containers.
- Monitoring dashboards, the TestZone browser UI, Loki, and Grafana are
  deployed by the monitoring workflow. TestZone's execution service and
  InfluxDB are deployed by the TestZone workflow. Backend deploys must not
  recreate any of them.
- Maintenance windows are monitoring control-plane state. The monitoring
  receiver persists their schedule and localized notices and exposes the
  unauthenticated, read-only `/status/api/v1/service-status` endpoint for
  customer apps. The monitoring deploy validates that this exact public route
  disables Basic Auth while maintenance administration and the rest of the
  monitoring workspace remain authenticated. Backend deployment and the
  application database do not own this state.
- Backend deployment has no Redis Stream Coordinator runtime dependency and
  must not provision coordinator containers, networks, routes, secrets, or
  readiness settings.
- Backend deployment owns one Redis runtime with AOF `everysec`, RDB snapshots,
  a retained Docker volume, and a password stored in AWS Secrets Manager.
  Redis publishes host port `6379`; the separate backend-network workflow
  restricts that port to the same approved administrator CIDRs as MySQL. Redis
  starts before the backend; Actions verifies only process survival and port
  publication while application readiness and Grafana verify runtime behavior.
- Scheduler readiness includes only jobs expected to succeed within the
  readiness freshness window. Daily correction jobs remain visible in run
  history and failure alerts but must not make a 15-minute readiness check stale.
  The answer-grading watchdog is a frequent critical job and belongs in the
  default monitored list.
- Runtime health checks are not GitHub Actions deploy gates. GitHub Actions may
  validate deploy mechanics such as image pull, container process survival, and
  nginx syntax only.
- Shared infrastructure changes, such as nginx routing needed by multiple
  modules, must be called out in the workflow summary and kept backwards
  compatible with currently running containers.

## Change Routing

- Backend Kotlin/API/env changes: build backend image, then run backend deploy.
- Backend runtime secrets are read by the backend deploy workflow from AWS
  Secrets Manager. The `buddystudy/prod` application secret owns
  `OPENAI_API_KEY_USER`, `OPENAI_API_KEY_SYSTEM`, `REDIS_PASSWORD`,
  `APNS_AUTH_KEY_BASE64`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`,
  and `APNS_ENV`. The two OpenAI keys must be present and different:
  post-study topic suggestions use only the system key, while question
  generation, embeddings, translation, answer feedback, and grading use only
  the user-content key. Required values must be validated before writing the
  container env file so an optional Spring config import cannot silently start
  a partially configured backend. APNs credentials must not be duplicated in
  GitHub Actions Secrets.
- MySQL credentials and connection URLs are owned by the
  `buddystudy/prod/mysql` secret. It contains `dbname`, `username`,
  `password`, `jdbcUrl`, and `r2dbcUrl`; the deploy workflow reads both JDBC
  and R2DBC settings from that secret. A legacy host password file is migrated
  into the secret once and is not the continuing configuration source.
- Production MySQL administration uses host port `3306`, restricted by the EC2
  security group to approved administrator CIDRs. The backend deploy verifies
  the existing MySQL container has that host-port binding. When it is missing,
  the workflow recreates only the container after verifying that
  `/var/lib/mysql` is backed by the persistent `buddystudy-mysql-data` volume;
  it never removes the volume.
- Production Redis administration uses host port `6379`, password
  authentication, and the same approved administrator CIDRs as MySQL. Run
  `Configure BuddyStudy Backend Network` before publishing the Redis port; it
  removes stale Redis CIDRs, mirrors the MySQL `3306` CIDRs, and does not alter
  unrelated ports or security groups.
- Both native and JVM backend images ship MySQL Flyway scripts at
  `/app/db/migration-mysql`. Both normal deployment and the one-time cutover
  bootstrap use `filesystem:/app/db/migration-mysql`, so schema discovery has
  the same behavior in both runtime modes and does not depend on native-image
  classpath resource scanning.
- The one-time PostgreSQL cutover preserves every question row. Legacy
  questions may reference a study, user, or concept that was already deleted
  before foreign keys existed; only those missing nullable references are
  normalized to `NULL`. The migration summary records each normalization count,
  while source and destination table counts must still match exactly.
- The backend deploy runs `buddystudy-db-metrics`, a port-free Docker CLI
  observer that logs MySQL container CPU, total/active connections, and
  the live `max_connections` setting every 30 seconds. Promtail forwards these
  `database_runtime` records to Loki. The observer receives its MySQL password
  through a private container environment, never logs it, and is not a
  Prometheus/exporter service.
- Backend exceptions logged at `ERROR` include the throwable and bounded
  operation identifiers. EC2 Promtail joins each Java stack trace into one Loki
  event, extracts `level=ERROR`, and preserves the full event for Grafana.
  Sentry receives the same ERROR and throwable through its Spring Boot 4
  Logback integration. `SENTRY_DSN` is supplied only through the backend deploy
  secret, and PII capture remains disabled. The backend application does not
  receive a Slack webhook and never sends operational alerts directly.
- Deployment notifications use the dedicated `DEPLOY_SLACK_WEBHOOK_URL` Slack
  app identity and show status, production environment, runtime, source commit,
  immutable image, actor, timestamp, and direct build/deploy run actions.
  Block Kit Markdown fields use actual newline characters so Slack renders
  labels and values on separate lines; payload text must not contain a literal
  `\n` sequence.
  `SLACK_WEBHOOK_URL` is only a migration fallback.
- iOS release notifications are sent through
  `Notify BuddyStudy Deployment Status` in the private deploy repository, so
  Slack credentials remain centralized. The channel receives one compact
  attachment with a colored left bar and the iOS release summary. A separate
  monitor follows the source workflow and posts four short, numbered IPA build,
  archive, and TestFlight progress replies in that message's thread. The parent
  keeps only the release, targets, source commit, and GitHub Actions link. A
  successful upload means App Store Connect accepted the binary; Apple
  processing continues asynchronously. `DEPLOY_SLACK_BOT_TOKEN` with `chat:write` and
  `DEPLOY_SLACK_CHANNEL_ID` enable thread replies. When either is absent, the
  incoming webhook posts only the compact parent summary.
- The backend deploy temporarily retains the `buddystudy-profile-photos`
  volume for legacy-file cleanup. New profile-photo uploads are disabled;
  saving a pixel avatar or deleting an account removes the user's legacy file.
- Admin frontend UI changes: build admin frontend image, then run admin frontend
  deploy.
- Grafana/Loki/API Logs/TestZone UI changes: run the monitoring deploy.
  The app repository dispatches this module through
  `monitoring-source-published` when an explicit `deploy/monitoring-*` tag is
  pushed.
  Grafana persists writable runtime state in the `buddystudy-grafana-data`
  Docker volume; dashboards and provisioning files remain read-only bind
  mounts from the monitoring release. Dashboard JSON files are replaced
  atomically and do not restart Grafana, Loki, or either gateway. The workflow
  compares service configuration before deployment and recreates only the
  service whose Loki, Grafana provisioning, Promtail, or nginx configuration
  actually changed. This prevents routine dashboard releases from producing
  transient Grafana query 502 responses. Every monitoring deploy synchronizes the
  persisted `admin` account password with `GRAFANA_ADMIN_PASSWORD`; changing
  the GitHub Actions secret therefore also changes the existing Grafana
  account instead of affecting only first initialization. The deploy records a
  bounded Grafana and monitoring gateway startup log for incident diagnosis
  without using either log as a health gate. Anonymous Grafana access stays
  disabled, and the deployment does not force a protected file dashboard as
  the anonymous home page. Unauthenticated visits therefore reach Grafana's
  login screen instead of rendering a dashboard shell that fails with
  `Unauthorized`. Grafana Live accepts WebSocket connections only from
  `https://grafana.lowfidev.cloud`, matching the public gateway origin. The
  The dedicated Grafana gateway restores `grafana.lowfidev.cloud`, HTTPS, and
  port 443 after Routingflare consumes the original host header. This keeps
  Grafana Live origin checks aligned with its public HTTPS `root_url`.
  Grafana also provisions a Loki alert for backend `level=ERROR` events and
  sends it to the `BuddyStudy Slack` contact point through the dedicated
  `GRAFANA_SLACK_WEBHOOK_URL` app identity. Slack contains the incident
  summary and a direct Grafana Explore link with the exact Loki ERROR query;
  Loki and Sentry retain the full stack and diagnostic context. The link does
  not depend on the Logs Drilldown volume API. This Grafana path is the only
  production path from backend application errors to Slack.
  The Server Dashboard supports fixed and explicit From/To time ranges and
  reads the same structured Micrometer runtime samples as the provisioned
  Grafana Server Runtime dashboard. The same module publishes the fixed,
  collapsible monitoring navigation and Settings. Access & Audit records access
  to the monitoring workspace itself, not application API traffic. Monitoring
  Nginx writes a bounded structured log for page views, denied authentication,
  and TestZone mutations; a module-local Promtail forwards it to Loki. Passwords
  and request bodies are never logged, and high-cardinality values such as IP,
  username, and path remain JSON fields instead of Loki labels.
  The same authenticated gateway serves one React monitoring shell for API
  Logs, API Performance, TestZone, Users & Quotas, Redis Streams, Access &
  Audit, and Settings. It proxies `/backend/api/` to the backend admin API.
  Users & Quotas provides bounded user search, 20-row pagination,
  membership-tier allowance editing, and per-user tier/allowance overrides.
  These controls are internal-only and must not be linked from the consumer
  app.
- Membership schema or quota API changes require a backend image/deploy first.
  Deploy the monitoring module separately after the backend rollout when the
  Users & Quotas UI changes; do not combine the two rollouts into one job.
- TestZone runner, InfluxDB integration, k6 validation, or component catalog
  changes: build `buddystudy-testzone`, then run the TestZone deploy.
  The deploy owns persistent local InfluxDB/component credentials under
  `MACBOOKAIR_TESTZONE_ROOT`. Target URL and all load settings are stored in
  the user-authored JavaScript and executed without runner-side injection.
- Monitoring hostname or port changes: run the monitoring routing workflow
  after the monitoring deploy. Routingflare maps
  `monitoring.lowfidev.cloud` to the monitoring nginx gateway and
  `grafana.lowfidev.cloud` to Grafana's dedicated gateway port. The Grafana
  gateway proxies Grafana and redirects legacy custom-dashboard paths such as
  `/system.html` to `monitoring.lowfidev.cloud`. The targets stay on separate
  ports because Routingflare's filtering proxy consumes the original `Host`
  header before forwarding to a local origin.
  The routing job also provisions both Cloudflare Tunnel CNAME records,
  enables Routingflare autostart, removes orphaned local connectors for the
  named tunnel, clears their stale Cloudflare connection records, and
  updates Routingflare before relaunching the menu-bar app through macOS
  Launch Services and starting one connector. Keeping the proxy current avoids
  invalid hop-by-hop response headers, while the clean process context lets the
  app and its tunnel outlive GitHub Actions orphan-process cleanup. This
  prevents both post-deploy 502 responses and requests alternating between
  current and obsolete ingress configurations.
- Cloudflare Health Monitor changes: deploy the Cloudflare Worker only.
  `SCHEDULED_CHECKS_ENABLED=false` is the production default; Grafana owns
  continuous outage alerting so Cron cannot consume Workers KV writes.
- Portfolio runtime or hostname changes: run
  `portfolio-site/scripts/setup-routingflare.sh` on the owning Mac. The
  production process is supervised by `launchd`, and Routingflare maps
  `buddystudy.lowfidev.cloud` to the local origin. This operation must not
  deploy the backend, monitoring stack, or admin frontend.
- Nginx public routing changes: update the owning module workflow template and
  state which module is responsible for reloading nginx.
