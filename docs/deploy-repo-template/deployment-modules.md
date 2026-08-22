# BuddyStudy Deployment Modules

Deployments are split by runtime ownership. Do not combine unrelated modules in
one workflow run just because they share a host.

## Modules

| Module | Workflow | Trigger | Runner | Owns |
| --- | --- | --- | --- | --- |
| Backend API | `Deploy BuddyStudy Backend` | `backend-image-published`, manual | EC2 self-hosted | Docker Swarm backend service rollout, backend env including the MCP feature flag and Host allowlist, fixed backend nginx route, standard application/runtime metrics, backend-log multiline collection |
| Translation server | `Deploy BuddyStudy Translation Server` | manual | EC2 self-hosted | Internal LibreTranslate runtime and persisted `ko`, `en`, `ja` model cache |
| Backend network | `Configure BuddyStudy Backend Network` | manual | EC2 self-hosted | Redis administrator ingress on the backend security group |
| Database cutover | `Migrate BuddyStudy PostgreSQL To MySQL` | manual, one-time | EC2 self-hosted | PostgreSQL backup, MySQL import, row-count and reference validation, automatic pre-cutover rollback |
| Flyway V32 recovery | `Repair BuddyStudy Backend Flyway V32` | manual, one-time | EC2 self-hosted | Guarded removal of only the failed V32 history row and V32 partial check constraints |
| Backend administrator recovery | `Reset BuddyStudy Backend Administrator` | manual, one-time | EC2 self-hosted | Activate only the fixed `admin` monitoring operator and replace its BCrypt password hash |
| Admin frontend | `Deploy BuddyStudy Admin Frontend` | `admin-frontend-image-published`, manual | EC2 self-hosted | Private `buddystudy-admin-frontend` container image submission only |
| iOS TestFlight | `Release iOS App` | `v*`, manual | GitHub-hosted macOS | Release planning, signed IPA build, artifact retention, and TestFlight upload as separate jobs |
| Monitoring receiver | `Deploy BuddyStudy Monitoring on MacBook Air` | manual | MacBook Air self-hosted | API Logs, API Performance, TestZone UI, deployment history, Grafana, Loki, ERROR-log Slack alerting, monitoring auth and access audit, and the backend/FRC maintenance operator UI |
| Redis Stream operations | `Deploy RedisStreamScope on MacBook Air` | manual | MacBook Air self-hosted | RedisStreamScope runtime, persisted SQLite/config volume, production Redis connection, and attachment to the existing monitoring gateway |
| Monitoring routing | `Deploy BuddyStudy Monitoring Routes on MacBook Air` | manual | MacBook Air self-hosted | Routingflare routes for the monitoring UI, Grafana, and RedisStreamScope |
| TestZone execution | `Deploy BuddyStudy TestZone on MacBook Air` | `testzone-image-published`, manual | MacBook Air self-hosted | k6 runner, script/project/run storage, InfluxDB, approved disposable test components |
| MacBook Air Docker capacity | `Maintain MacBook Air Docker Capacity` | manual, weekly | MacBook Air self-hosted | Docker daemon readiness/recovery, host and Docker capacity diagnostics, and reclamation of old images unreferenced by every container |
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
- The stateless MCP endpoint is part of the Backend API module and remains
  disabled in production unless the repository variable
  `MCP_SERVER_ENABLED=true`. Enabling it must not add a container, route,
  workflow health check, or monitoring deployment; the backend workflow injects
  the public backend Host allowlist and existing Grafana/Loki monitoring owns
  runtime detection. The checked-in Kubernetes/k3s manifests also pin
  `MCP_SERVER_ENABLED=false`; a local or alternate deployment must opt in
  explicitly regardless of the active Spring profile.
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
- Backend administrator recovery requires the exact `RESET admin` confirmation
  and a temporary cost-12 BCrypt hash in
  `ADMIN_RECOVERY_PASSWORD_BCRYPT_HASH`. It must never accept or print a
  plaintext password, and the temporary secret must be deleted after a
  successful reset.
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
- MacBook Air Docker storage remains module-owned even though monitoring,
  TestZone, and Redis Stream Scope share one Docker Desktop VM. Each
  module configures the Docker `local` log driver at 10 MiB times three files
  for the containers it owns; the monitoring gateway additionally rotates its
  structured access log at 8 MiB and error log at 2 MiB, retaining three
  archives of each. Monitoring owns Loki's seven-day retention, TestZone owns
  its 30-day InfluxDB retention and run artifacts, and Redis Stream Scope owns
  its persistent application volume. One module may report the other
  modules' directory/container sizes for capacity diagnosis, but it must not
  delete or recreate their containers, volumes, or persisted data.
- Host-wide Docker image reclamation belongs only to `Maintain MacBook Air
  Docker Capacity`; monitoring, TestZone, and Redis Stream Scope deployments
  must not perform it. The maintenance workflow runs weekly or manually,
  recovers Docker Desktop when its daemon is unavailable, reports host and
  Docker storage before and after maintenance, and prunes only images older
  than seven days that are unreferenced by every running or stopped container.
  It never prunes containers, volumes, networks, build cache, TestZone
  artifacts, Loki data, Grafana data, incident records, or host files. Removed
  images are recoverable by pulling them again from their registries. Any data
  retention policy change remains owned by its module's separate workflow.
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
  `billing-fulfillment-recovery` and `billing-subscription-event-projector`
  managed jobs, which also belong in the default
  monitored list so a verified charge cannot remain unfulfilled silently.
  RevenueCat webhook recovery requires `REVENUECAT_WEBHOOK_SIGNING_SECRET` in
  the backend application secret. Subscription reconciliation additionally
  requires `REVENUECAT_PROJECT_ID` and `REVENUECAT_SERVER_API_KEY`.
  `REVENUECAT_APP_ID` is optional scoping metadata; an empty app ID accepts all
  HMAC-authenticated apps in the BuddyStudy RevenueCat project and still rejects
  unknown products or account tokens. The webhook is the primary new-purchase and lifecycle input and uses
  the same provider transaction idempotency key as the backward-compatible
  direct JWS synchronization path. The iOS app always uses the App Store
  `appl_` public SDK key. App Review candidate and ordinary TestFlight Release
  builds default to the production API, and a new version/build clears stale
  developer routing before its first request. The production RevenueCat webhook
  integration must therefore deliver both App Store Production and Sandbox
  events to the production endpoint with its existing HMAC secret. Do not leave
  a second Sandbox-only development integration delivering those same events,
  because duplicate delivery would split one purchase lifecycle across two
  ledgers. The development endpoint remains scoped to Xcode-local billing.
- Runtime health checks are not GitHub Actions deploy gates. GitHub Actions
  validates image/config submission and Nginx syntax only. Docker Swarm owns
  task health, replacement ordering, and rollback; Grafana owns continuous
  outage alerting. Admin container deployments verify only the configured image;
  they must not use container state, readiness, or an HTTP check as an Actions
  success condition. A successful workflow is reported as staged, promoted, or
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
- Admin frontend UI changes: build the immutable admin frontend image, then run
  the EC2 admin frontend deploy. The deploy replaces only the private
  `buddystudy-admin-frontend` container attached to `buddystudy-swarm-net` and
  verifies its configured image; Grafana, rather than GitHub Actions, observes
  runtime readiness.
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
  `GRAFANA_SLACK_WEBHOOK_URL`. Grafana's built-in Slack receiver is not used
  because it forces the clickable message title to the static alert-rule
  `GeneratorURL`. A custom webhook payload instead renders only the concise
  incident summary and `해당 오류 로그 보기` link. API alert links query the
  exact `requestId`; background alert links query the original
  millisecond-precision timestamp and logger. Both links carry an absolute Loki
  range from two minutes before to two minutes after the event, so reopening a
  Slack notification does not move the search window to the current time.
  The parser accepts both the application and worker-thread brackets in Spring
  log prefixes and excludes entries unless the timestamp plus request ID or
  logger were extracted. A malformed log line therefore cannot collapse into
  an unlabeled alert that builds an Explore query from `[no value]`. If Grafana
  itself emits an evaluation alert without a concrete log identity, Slack does
  not fabricate a log or alert-rule link and the Codex incident receiver rejects
  it instead of searching or dispatching unrelated ERROR logs.
  A compact `해당 오류 로그 보기` hyperlink targets the Loki ERROR
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
  The gateway resolves the public backend origin through Docker DNS on each
  request cache interval so a backend address change does not leave the
  long-running monitoring container pinned to a stale upstream IP.
  The deploy restarts Docker Desktop when its daemon is unavailable, bounds
  Docker calls and their child processes, validates Nginx configuration before
  replacement, and records the applied gateway-config hash only after the
  replacement succeeds. If a normal restart cannot stop orphaned Docker
  helpers, the workflow uses Docker Desktop's scoped force-stop before starting
  the daemon again. Docker Desktop starts without the Actions runner tracking
  identifier so job cleanup cannot terminate the recovered daemon.
  Every monitoring deployment recreates only the API Dashboard gateway and its
  log-rotation sidecar after Docker is ready. This gives Nginx a fresh private
  bridge endpoint after a Docker Desktop restart while leaving Loki, Grafana,
  Promtail, and the incident receiver untouched unless their config or bounded
  logging policy changed. The workflow reports filesystem, Docker, monitoring,
  and TestZone capacity before submission, leaves host-wide image reclamation
  to `Maintain MacBook Air Docker Capacity`, and does not use HTTP or
  container-health checks as an Actions gate.
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
