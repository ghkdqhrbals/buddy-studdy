# BuddyStudy Deployment Modules

Deployments are split by runtime ownership. Do not combine unrelated modules in
one workflow run just because they share a host.

## Modules

| Module | Workflow | Trigger | Runner | Owns |
| --- | --- | --- | --- | --- |
| Backend API | `Deploy BuddyStudy Backend` | `backend-image-published`, manual | EC2 self-hosted | Docker Swarm backend service rollout, backend env, fixed backend nginx route, standard application/runtime metrics, backend-log multiline collection |
| Translation server | `Deploy BuddyStudy Translation Server` | manual | EC2 self-hosted | Internal LibreTranslate runtime and persisted `ko`, `en`, `ja` model cache |
| Backend network | `Configure BuddyStudy Backend Network` | manual | EC2 self-hosted | Redis administrator ingress on the backend security group |
| Database cutover | `Migrate BuddyStudy PostgreSQL To MySQL` | manual, one-time | EC2 self-hosted | PostgreSQL backup, MySQL import, row-count and reference validation, automatic pre-cutover rollback |
| Flyway V32 recovery | `Repair BuddyStudy Backend Flyway V32` | manual, one-time | EC2 self-hosted | Guarded removal of only the failed V32 history row and V32 partial check constraints |
| Admin frontend | `Deploy BuddyStudy Admin Frontend` | `admin-frontend-image-published`, manual | EC2 self-hosted | Admin frontend container only |
| iOS TestFlight | `Release iOS App` | `v*`, manual | GitHub-hosted macOS | Release planning, signed IPA build, artifact retention, and TestFlight upload as separate jobs |
| Monitoring receiver | `Deploy BuddyStudy Monitoring on MacBook Air` | manual | MacBook Air self-hosted | API Logs, API Performance, TestZone UI, deployment history, Grafana, Loki, ERROR-log Slack alerting, monitoring auth and access audit, and the backend/FRC maintenance operator UI |
| Redis Stream operations | `Deploy RedisStreamScope on MacBook Air` | manual | MacBook Air self-hosted | RedisStreamScope runtime, persisted SQLite/config volume, production Redis connection, and attachment to the existing monitoring gateway |
| Monitoring routing | `Deploy BuddyStudy Monitoring Routes on MacBook Air` | manual | MacBook Air self-hosted | Routingflare routes for the monitoring UI, Grafana, and RedisStreamScope |
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
- LibreTranslate is an internal-only runtime on `buddystudy-net`. Its workflow
  owns the `buddystudy-libretranslate` container and model volume; the backend
  workflow only injects
  `BUDDYSTUDY_TRANSLATION_BASE_URL=http://buddystudy-libretranslate:5000`.
  When Swarm is active, the translation workflow also attaches the same
  standalone container to `buddystudy-swarm-net` so translation redeploys do
  not disconnect the backend service.
  The translation workflow pins a multi-architecture image and never publishes
  the service port on the host.
- A job must have a module-specific name such as `deploy_backend`,
  `deploy_admin_frontend`, or `deploy_monitoring`.
- The iOS release workflow remains an iOS-only module. It separates release
  planning, the signed IPA build, TestFlight upload, and completion reporting
  into dependent jobs so each failure boundary is visible without combining
  backend, monitoring, or admin deployment.
- The iOS archive uses the installed Apple Distribution certificate and App
  Store provisioning profile with manual signing. Archive creation must not
  ask Apple to create or revoke development certificates; the App Store
  Connect API key is reserved for upload and version-management operations.
- Backend image build remains in the app repository on GitHub-hosted runners.
- Backend images support `native` and `jvm` runtime modes from one Dockerfile.
  JVM is the default for tag-triggered and manually dispatched releases. A
  manual image build may explicitly select native. Every build stamps the
  runtime into `io.buddystudy.backend.runtime` and dispatches that value with
  the immutable runtime-qualified image reference (`<tag>-native` or
  `<tag>-jvm`). The
  unqualified tag remains a compatibility alias. The deploy workflow verifies
  the label before rollout, so changing runtime does not change the backend
  environment or routing contract.
- Production may explicitly select the JVM runtime without changing the API or
  routing contract. The backend image workflow must receive
  `backend_runtime=jvm`; the deploy workflow verifies that runtime label before
  replacing the container.
- EC2 self-hosted runners are deploy-only. They pull images and restart
  containers, but must not compile backend code or build Docker images.
- The backend application is a single-replica Docker Swarm service named
  `buddystudy_backend`. Updates use `start-first`, the image dependency health
  check, a five-second post-readiness monitor window, and automatic rollback.
  The deployment workflow waits for Swarm to report `UpdateStatus=completed`,
  verifies `1/1` replicas, and requires the running task image to match the
  requested immutable release before it reports success. A paused or rolled
  back update fails the workflow and its deployment notification. This
  prevents an unhealthy replacement task from taking traffic while retaining
  the previous task during the update. A single Swarm node provides deployment
  continuity, not host-level high availability.
- The backend task is limited to 1.25 GiB memory and reserves 512 MiB so the
  old and new task can overlap on the 4 GiB EC2 host without allowing two JVMs
  to consume the entire machine. JVM heap remains 50% of its container limit.
- Nginx routes to the fixed `buddystudy_backend:8080` service name on the
  attachable `buddystudy-swarm-net` overlay. Ordinary backend updates never
  rewrite the upstream. The first migration is staged with the old route
  intact and requires one explicit `promote_swarm=true` run after the staged
  task has been inspected. Promotion routes to that existing task without
  resubmitting the stack.
- Before pulling a backend release, the backend deploy removes only Docker
  images that are not referenced by any container. This keeps the small EC2
  disk from accumulating superseded release images without touching running
  or rollback containers.
- Backend application deploys preserve the existing Redis container and data
  volume. Redis must not be recreated as part of a backend rollout because the
  resulting dependency outage makes Swarm reject otherwise healthy backend
  tasks.
- Monitoring dashboards, the TestZone browser UI, Loki, and Grafana are
  deployed by the monitoring workflow. TestZone's execution service and
  InfluxDB are deployed by the TestZone workflow. Backend deploys must not
  recreate any of them.
- Maintenance windows are backend application state. The authenticated
  monitoring UI writes through the backend admin API, the backend persists the
  schedule and localized notices, and then publishes the current policy to
  Firebase Remote Config. iOS consumes only Firebase Remote Config. Monitoring
  must not deploy a separate service-status server or expose a public status
  endpoint.
- Backend deployment uses Redis Streams directly through the application
  consumers. It must not provision a separate stream coordination service,
  related containers, networks, routes, secrets, or readiness settings.
- Active Redis Stream keys are event-specific and must follow
  `<business-domain>.<data-type>.<event-type>.<version>`. Deployment may keep
  the five former `buddystudy-*-v1` keys readable only while their pending
  entries drain; it must never publish new events to those legacy keys.
- Backend deployment owns one Redis runtime with AOF `everysec`, RDB snapshots,
  a retained Docker volume, and a password stored in AWS Secrets Manager.
  Redis publishes host port `6379`; the separate backend-network workflow
  restricts that port to the same approved administrator CIDRs as MySQL. Redis
  starts before the backend; Actions verifies only process survival and port
  publication while application readiness and Grafana verify runtime behavior.
- Scheduler readiness includes every registered managed job, all of which are
  expected to succeed within the readiness freshness window. Admin analytics
  aggregation is an authenticated on-demand backend operation and is not
  registered as a scheduled or manually retryable batch job.
  The answer-grading watchdog is a frequent critical job and belongs in the
  default monitored list. Apple payment evidence is recovered by the
  `billing-fulfillment-recovery` managed job, which also belongs in the default
  monitored list so a verified charge cannot remain unfulfilled silently.
  RevenueCat webhook recovery requires `REVENUECAT_WEBHOOK_SIGNING_SECRET`,
  `REVENUECAT_PROJECT_ID`, and `REVENUECAT_APP_ID` in the backend application
  secret. The webhook is a secondary input and uses the same Apple transaction
  idempotency key as direct JWS synchronization.
- Runtime health checks are not GitHub Actions deploy gates. GitHub Actions
  validates image/config submission and Nginx syntax only. Docker Swarm owns
  task health, replacement ordering, and rollback; Grafana owns continuous
  outage alerting. A successful workflow is reported as staged, promoted, or
  submitted rather than as runtime-health completion.
- Backend workflow lifecycle events are written to the monitoring TestZone
  store with the stable `<deploy-repository>:<run-id>` key. Start and terminal
  events update one record containing source, image, runtime, actor, phase,
  duration, and Actions URL. The browser reads this bounded history through the
  authenticated monitoring gateway. Event ingestion is exposed only on the
  exact POST route `/deployment-events/events` and requires the dedicated
  `MONITORING_DEPLOYMENT_INGEST_TOKEN`; it does not expose Docker control or
  replace Grafana runtime alerting.
- Shared infrastructure changes, such as nginx routing needed by multiple
  modules, must be called out in the workflow summary and kept backwards
  compatible with currently running containers.

## Change Routing

- Backend Kotlin/API/env changes: build backend image, then run backend deploy.
- LibreTranslate image, language-model, or container changes: run the
  translation-server deploy independently. Deploy it before a backend release
  that first depends on its Docker network address.
- Backend runtime secrets are read by the backend deploy workflow from AWS
  Secrets Manager. The `buddystudy/prod` application secret owns
  `OPENAI_API_KEY_USER`, `OPENAI_API_KEY_SYSTEM`, `REDIS_PASSWORD`,
  `APNS_AUTH_KEY_BASE64`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`,
  `APNS_ENV`, `FIREBASE_PROJECT_ID`, `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`,
  `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `SMTP_FROM`.
  The Firebase service account must be limited to reading and updating Remote
  Config templates. The two OpenAI keys must be present and different:
  post-study topic suggestions use only the system key, while question
  generation, embeddings, translation, answer feedback, and grading use only
  the user-content key. Required values must be validated before writing the
  container env file so an optional Spring config import cannot silently start
  a partially configured backend. SMTP values are also injected explicitly so
  email signup cannot deploy with an empty sender. APNs and SMTP credentials
  must not be duplicated in GitHub Actions Secrets.
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
- Production does not run a custom MySQL metrics container. Database pressure
  is observed through the backend's standard Micrometer R2DBC pool gauges,
  including allocated, acquired, idle, pending, and configured maximum
  connections. Adding a database exporter requires an explicitly approved
  Prometheus-compatible metrics backend; do not emulate one with a custom
  Docker CLI polling script.
- Backend exceptions logged at `ERROR` include the throwable and bounded
  operation identifiers. EC2 Promtail joins each Java stack trace into one Loki
  event, extracts `level=ERROR`, and preserves the full event for Grafana.
  Sentry receives the same ERROR and throwable through its Spring Boot 4
  Logback integration. `SENTRY_DSN` is supplied only through the backend deploy
  secret, and PII capture remains disabled. The backend application does not
  receive a Slack webhook and never sends operational alerts directly.
- Deployment notifications use the dedicated `DEPLOY_SLACK_WEBHOOK_URL` Slack
  app identity. Backend deployment posts the same compact attachment pattern as
  iOS: one status line with environment and runtime, followed by the deploy run
  and source commit links. It does not override the Slack app identity, include
  emoji, repeat the image reference, or render action buttons.
  A successful Swarm rollout is reported as `배포 완료` only after the rollout
  waiter reaches `rollout-completed`; intermediate submission states must not
  be presented as completion.
  `SLACK_WEBHOOK_URL` remains only a migration fallback.
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
  dedicated Grafana gateway restores `grafana.lowfidev.cloud`, HTTPS, and
  port 443 after Routingflare consumes the original host header. This keeps
  Grafana Live origin checks aligned with its public HTTPS `root_url`.
  Grafana also provisions a Loki alert for backend `level=ERROR` events and
  sends it to the `BuddyStudy Slack` contact point through the dedicated
  `GRAFANA_SLACK_WEBHOOK_URL` app identity. Slack contains the incident
  summary, the millisecond-precision timestamp parsed from the original log,
  and the error location. API incidents show the HTTP method, full production
  request URL, and root stack-frame location; background incidents show the
  logger component. API alert links query the exact `requestId`; background
  alert links query the original millisecond timestamp and logger. Their Explore
  range starts at the captured event timestamp rather than a moving relative
  window, so reopening a Slack notification still targets the same Loki event.
  A compact `오류 로그 보기` hyperlink targets the Loki ERROR
  event without printing the raw Explore URL in the message. API and background
  failures are separate alert rules so request metadata is never fabricated for
  scheduler, stream-consumer, or application-startup failures.
  Loki and Sentry retain the full stack and diagnostic context. The link does
  not depend on the Logs Drilldown volume API. This Grafana path is the only
  production path from backend application errors to Slack. The same contact
  point also sends an HMAC-signed request over the private Monitoring Docker
  network to `buddystudy-incident-receiver`. The receiver deduplicates the
  Grafana alert instance, reads bounded redacted Loki context and the latest
  successful backend deployment SHA, then dispatches
  `codex-incident-autofix` to the source repository. The Codex job is
  read-only with respect to GitHub, full backend verification runs in a
  separate job, and only a final isolated job may open a labeled Draft PR.
  It never merges or deploys automatically. Configure
  `GRAFANA_INCIDENT_HMAC_SECRET` and `CODEX_AUTOFIX_GITHUB_TOKEN` in the
  private deploy repository, and `OPENAI_API_KEY_CODEX_AUTOFIX` plus the
  optional `CODEX_AUTOFIX_SLACK_WEBHOOK_URL` in the source repository.
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
  Logs, API Performance, TestZone, Deployments, Users & Quotas, Redis Streams,
  Access & Audit, Administrators, and Settings. It proxies `/backend/api/` to
  the backend admin API. The React shell owns a single login page and redirects
  an unauthenticated visit back to its original destination after login. The
  backend database stores administrator accounts as BCrypt hashes; the legacy
  environment administrator is imported on its first successful login.
  Monitoring Nginx validates that same bearer session before forwarding Loki
  and TestZone requests, so dashboard Basic Auth and `.htpasswd` deployment
  secrets are not used. Deployments auto-refreshes every ten seconds and keeps
  a maximum of 500 workflow records in TestZone's persisted data directory.
  Users & Quotas provides bounded user search, 20-row pagination,
  membership-tier allowance editing, recurring per-user tier/allowance
  overrides, and a separate question-limit override that expires at the
  selected user's next quota reset.
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
  after the owning runtime deploy. Routingflare maps
  `monitoring.lowfidev.cloud` to the monitoring nginx gateway and
  `grafana.lowfidev.cloud` to Grafana's dedicated gateway port, and
  `redis.lowfidev.cloud` to the existing monitoring Nginx container's
  RedisStreamScope listener on `127.0.0.1:3002`. RedisStreamScope itself
  remains reachable only on the shared private monitoring Docker network and
  persists application state in
  `buddystudy-redisstreamscope-data`. The Grafana
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
- RedisStreamScope runtime changes: run `Deploy RedisStreamScope on MacBook
  Air` independently, then run the monitoring routing workflow only when the
  public hostname or origin port changed. The deploy pulls an immutable
  multi-architecture GHCR digest on the MacBook Air; it does not build source
  on the self-hosted runner. The production Redis node list and password come
  from the standalone `RSC_REDIS_HOST` and `RSC_REDIS_PASSWORD`. Public traffic passes
  the existing monitoring Nginx gateway and uses RedisStreamScope's own session
  authentication. GitHub Actions submits the container without using an HTTP
  health check as a deployment gate.
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
