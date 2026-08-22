# BuddyStudy Deploy

Deployment repository for BuddyStudy runtime modules.

This repo is triggered by `repository_dispatch` from the app repository after
module Docker images are published to GHCR.

The public API domain is `https://api.ghkdqhrbals.org`.

## Deployment Modules

Deployments are module-scoped. Backend, admin frontend, monitoring, and health
monitor changes must be deployed through separate workflows/jobs. Start with
`deployment-modules.md` before adding or changing deploy workflows.

Current workflow templates:

- `deploy-backend.yml`: backend API runtime on EC2 with one compact,
  emoji-free Slack result attachment.
- `reset-backend-admin.yml`: guarded one-time recovery of the persisted backend
  monitoring administrator credential.
- `backend-swarm-stack.yml`: backend Swarm service update, health, and rollback
  policy consumed by `deploy-backend.yml`.
- `repair-backend-flyway-v32.yml`: guarded one-time cleanup for a failed,
  partially applied V32 migration.
- `configure-backend-network.yml`: Redis administrator ingress on the backend
  EC2 security group.
- `deploy-admin-frontend.yml`: private admin frontend container image
  submission on the backend EC2 host.
- `notify-deployment-status.yml`: centralized Slack status receiver for
  one compact iOS release summary and concise threaded progress replies. Set
  `DEPLOY_SLACK_BOT_TOKEN` and `DEPLOY_SLACK_CHANNEL_ID`; the incoming webhook
  remains a parent-summary fallback.
- `deploy-macbookair-monitoring.yml`: API Logs dashboard, Grafana, and Loki on
  MacBook Air.
- `deploy-macbookair-redisstreamscope.yml`: Redis Streams operations console
  attached to the existing monitoring gateway on MacBook Air.
- `deploy-macbookair-monitoring-routing.yml`: Routingflare public hostnames for
  monitoring, Grafana, and RedisStreamScope.
- `deploy-testzone.yml`: TestZone k6 execution service and InfluxDB on MacBook
  Air.
- `migrate-testzone-component-logging.yml`: guarded, one-time conversion of
  the two legacy TestZone PostgreSQL/Redis component containers from
  `json-file` to bounded Docker `local` logging.
- `maintain-macbookair-docker-capacity.yml`: weekly or manual host-wide Docker
  daemon recovery, capacity diagnostics, and bounded unused-image and old
  build-cache reclamation on MacBook Air.
- `retire-macbookair-kubernetes.yml`: guarded, two-run retirement of only the
  legacy Docker Desktop Kubernetes runtime on the MacBook Air.

## Required Secrets

Backend deploy:

- `GHCR_USERNAME`
- `GHCR_TOKEN`
- `BACKEND_MASTER_KEY`
- `BACKEND_API_TOKEN`
- `OPENAPI_ACCESS_TOKEN` (optional, only if docs API endpoint is enabled)
- `GOOGLE_IOS_CLIENT_ID`
- `ADMIN_RECOVERY_PASSWORD_BCRYPT_HASH` (temporary; only for the guarded
  backend administrator recovery workflow, then delete it)

Backend application values are stored in AWS Secrets Manager secret
`buddystudy/prod`. Required APNs keys are `APNS_AUTH_KEY_BASE64`,
`APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID`, and `APNS_ENV`. Firebase
Remote Config publication requires `FIREBASE_PROJECT_ID` and a Base64-encoded
service-account JSON in `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`. The service
account needs only Remote Config template read/update access. The deploy
workflow reads and validates these values before writing the backend
environment file; do not duplicate them as GitHub Actions Secrets.

EC2 log forwarding to MacBook Air Loki:

- `REMOTE_LOKI_BASIC_AUTH_USER` (optional, only if the Loki endpoint is protected with basic auth)
- `REMOTE_LOKI_BASIC_AUTH_PASSWORD` (optional, only if the Loki endpoint is protected with basic auth)

MacBook Air monitoring deploy:

- `GRAFANA_ADMIN_PASSWORD`
- `GRAFANA_INCIDENT_HMAC_SECRET`
- `CODEX_AUTOFIX_GITHUB_TOKEN` (fine-grained token scoped to dispatch the BuddyStudy source repository workflow)

MacBook Air Docker Desktop Kubernetes retirement:

- `MACBOOKAIR_K8S_RETIREMENT_BACKUP_KEY` (dedicated random value of at least 32
  characters; keep it after retirement because it decrypts the local recovery
  bundle and authenticates the retained Docker.raw clone)

BuddyStudy source repository incident auto-fix:

- `OPENAI_API_KEY_CODEX_AUTOFIX` (dedicated to `openai/codex-action`; never reuse backend OpenAI keys)
- `CODEX_AUTOFIX_SLACK_WEBHOOK_URL` (optional Draft PR notification)

MacBook Air RedisStreamScope deploy:

- `RSC_REDIS_HOST`
- `RSC_REDIS_PASSWORD`

MacBook Air TestZone deploy:

- `GHCR_USERNAME`
- `GHCR_TOKEN`

InfluxDB and component credentials are generated once on the MacBook Air with
mode `0600`, under
`MACBOOKAIR_TESTZONE_ROOT`, and are reused by later TestZone and monitoring
deploys.

Repository variables:

- `REMOTE_LOKI_PUSH_URL`: remote Loki push endpoint consumed by the EC2 promtail sender, for example `http://100.79.59.22:3100/loki/api/v1/push` over Tailscale or `https://loki.lowfidev.cloud/loki/api/v1/push` when protected by a tunnel.
- `MACBOOKAIR_MONITORING_ROOT`: persistent host path for MacBook Air PLG data, defaults to `$HOME/data/buddystudy/monitoring`.
- `REDISSTREAMSCOPE_PORT`: loopback-only RedisStreamScope gateway port,
  defaults to `3002`.
- `GRAFANA_PORT`: MacBook Air Grafana host port, defaults to `3000`.
- `LOKI_PORT`: MacBook Air Loki host port, defaults to `3100`.
- `MACBOOKAIR_TESTZONE_ROOT`: persistent TestZone and InfluxDB path.
- `TESTZONE_INFLUX_ORG` and `TESTZONE_INFLUX_BUCKET`: Grafana and runner
  storage coordinates.

## Runtime Layout

- `buddystudy-nginx`: public HTTPS proxy on host port `443`.
- `buddystudy_backend`: single-replica Docker Swarm service for the Spring Boot
  app on overlay network port `8080`.
- `buddystudy-swarm-net`: attachable overlay shared by Nginx, the backend
  service, MySQL, Redis, and LibreTranslate.
- `buddystudy-db`: MySQL on Docker network port `3306`, published to host port
  `3306` for approved administrator CIDRs.
- `buddystudy-redis`: password-protected Redis on Docker network port `6379`,
  published to host port `6379` for the same approved administrator CIDRs.
- `buddystudy-mysql-data`: persistent Docker volume for MySQL data.
- `buddystudy-redis-data`: persistent Docker volume for Redis AOF/RDB data.
- `buddystudy-backend-data`: legacy SQLite volume, kept for historical safety and not deleted.
- `buddystudy-promtail`: lightweight EC2 log sender. It scrapes Docker logs and forwards them to the MacBook Air Loki endpoint when `REMOTE_LOKI_PUSH_URL` is set.
- `buddystudy-incident-receiver`: private Monitoring-network service that verifies Grafana HMAC alerts, enriches them from Loki and deployment history, deduplicates alert instances, and dispatches the bounded Codex auto-fix workflow. It has no published host port.
- `buddystudy-mysql-data` retains live DB data across restarts and redeploys.
- Nginx proxies `/health`, `/api/v1/health`, and `/api/v1/*` to the BuddyStudy Spring Boot app.
- Other paths return 404 at Nginx.

## EC2 Runner Bootstrap

Use `ec2-user-data-self-hosted-runner.sh` as the EC2 launch template user data.
Replace these placeholders before launching the instance:

```text
__GITHUB_OWNER__
__GITHUB_REPO__
__GITHUB_PAT__
```

The script installs Docker and a GitHub Actions self-hosted runner under
`/opt/actions-runner`, then registers this systemd unit:

```text
buddystudy-github-runner.service
```

The service starts automatically on every EC2 reboot. The EC2 runner is
deploy-only. It must pull GHCR images and run containers, but must not build
backend code or Docker images.

The backend workflow expects the EC2 runner to match:

```yaml
runs-on: [self-hosted, Linux, ARM64, ec2]
```

Use an ARM instance such as `t4g.medium` when backend, MySQL, Redis, Nginx, and
Promtail share the host.

## Admin Frontend Deploy

The admin frontend is deployed separately from the backend. Copy
`deploy-admin-frontend.yml` into the deploy repository's `.github/workflows/`
directory. The app repository's `Build Admin Frontend Image` workflow dispatches
`admin-frontend-image-published` and waits for **Deploy BuddyStudy Admin
Frontend** on the EC2 deploy-only runner.

The admin deploy workflow owns only the private `buddystudy-admin-frontend`
container attached to `buddystudy-swarm-net`, where the backend Nginx serves it
under `https://api.ghkdqhrbals.org/admin/`. It pulls the immutable image,
replaces only that container, and verifies its configured image without waiting
for container state, readiness, or an HTTP health check. Grafana owns runtime
outage monitoring. The workflow must not rebuild the backend or recreate MySQL,
Loki, or Grafana.

## Monitoring Deploy

Monitoring is deployed separately from backend image rollout and runs on the
MacBook Air, not on EC2. Copy `deploy-macbookair-monitoring.yml` into the
deploy repository's `.github/workflows/` directory and run
**Deploy BuddyStudy Monitoring on MacBook Air** manually.

The MacBook Air runner must have labels:

```yaml
runs-on: [self-hosted, macOS, ARM64, macbook-air, monitoring]
```

The MacBook Air workflow creates or replaces:

- `buddystudy-api-dashboard`: API Logs dashboard reverse proxy using the
  backend administrator bearer session.
- `buddystudy-monitoring-log-rotator`: isolated sidecar that bounds the
  dashboard gateway access/error log files and signals Nginx to reopen them.
- `buddystudy-monitoring-promtail`: module-local collector for the two exact
  gateway log paths.
- `buddystudy-loki`: Loki with persistent host data under
  `$HOME/data/buddystudy/monitoring/loki/data` by default.
- `buddystudy-grafana`: Grafana with persistent host data under
  `$HOME/data/buddystudy/monitoring/grafana/data` by default.
- `buddystudy-incident-receiver`: Grafana ERROR webhook receiver with persistent incident reservations under `$HOME/data/buddystudy/monitoring/incident-receiver/data` by default.

Every MacBook Air module bounds its own container stdout/stderr with Docker's
`local` driver at 10 MiB times three files. The monitoring deploy reports host,
Docker, monitoring, and TestZone storage use but does not perform host-wide
Docker storage reclamation. It always recreates only the API Dashboard gateway
and its rotation sidecar after Docker is ready so a Docker Desktop restart
cannot leave the long-running Nginx bridge endpoint stale. TestZone and
RedisStreamScope policy changes still require their own module workflows; no
runtime HTTP or container-health gate is added here.

## MacBook Air Docker Capacity Maintenance

Copy `maintain-macbookair-docker-capacity.yml` into the deploy repository. It
runs weekly and also supports a manual dispatch. The workflow reuses the scoped
Docker Desktop restart recovery used by MacBook Air deploys, reports `df` and
`docker system df --verbose` before and after maintenance, then runs only
`docker image prune -a --filter until=168h` and
`docker builder prune -a --filter until=168h --force`. Docker therefore removes
images created more than seven days ago only when no running or stopped
container references them, and removes all unused build cache only after it is
more than seven days old.

This host-capacity workflow never prunes active/in-use build cache, containers,
volumes, networks, persisted module data, or host files. Images removed by the
maintenance are recoverable by pulling them again from their registries, and
removed build cache is recoverable by rebuilding. Monitoring, TestZone, and
RedisStreamScope workflows remain responsible for their own log and data
retention and must not duplicate host-wide Docker storage reclamation. Docker
daemon readiness is the only runtime prerequisite; the workflow does not make
HTTP or container-health checks.

The separate TestZone workflow creates or replaces:

- `buddystudy-testzone-service`: bounded k6 runner and JavaScript workspace API.
- `buddystudy-testzone-influxdb`: 30-day TestZone time-series storage.
- approved disposable MySQL, Redis, or Kafka containers only when a user
  deploys them from TestZone.

## MacBook Air Docker Desktop Kubernetes Retirement

Copy `retire-macbookair-kubernetes.yml` and
`scripts/retire_macbookair_kubernetes.py` into the deploy repository. This is
a one-time infrastructure module and is manual-only. It runs exclusively when
GitHub Actions supplies the exact `macbook-air-buddystudy` ARM64 runner
identity. Local laptops and every other runner fail closed before inspection or
mutation.

Retirement is deliberately a two-run operation:

1. Dispatch **Retire MacBook Air Docker Desktop Kubernetes** with the default
   `apply=false`. The read-only run prints a SHA-256 desired-state digest and
   the exact non-secret workload/PVC plan.
2. Review every identity and blocker. Dispatch a separate run with
   `apply=true`, paste that 64-character digest into
   `expected_inventory_digest`, and enter exactly
   `RETIRE DOCKER DESKTOP KUBERNETES` as the confirmation. Any desired-state
   change between runs invalidates the plan.

Immediately before the first cluster mutation, apply repeats the full
inventory, digest, blocker, settings, Docker.raw-path, and unrelated Docker
identity checks. A change at that final boundary aborts without scaling a
workload.

The preflight accepts only kubectl context `docker-desktop`, its local
`127.0.0.1:6443` or `localhost:6443` API server, namespace `buddystudy`, and
the known auxiliary Deployment
`default/buddystudy-redis-stream-coordinator`. Its replica state and manifest
are included in the encrypted backup and automatic rollback. Any other user
workload outside `buddystudy`, active Job, standalone Pod, ReplicationController,
or BuddyStudy DaemonSet blocks apply. Pods, ReplicaSets, Jobs, Events, and
status churn are excluded from the independent desired-state digest, while
workload/PVC/PV/Secret/ConfigMap resource versions and desired specs are
included.

Apply first suspends the recorded CronJobs, immediately checks all non-system
namespaces for an active Job, and only then stops writer Deployments, including
the exact default-namespace coordinator. A Job that races with suspension
causes rollback before writer scaling. After writer Pods have terminated, the
helper creates verified gzip logical dumps for every running PostgreSQL/MySQL
container. Redis backup requires `SAVE` and an in-container
`redis-check-rdb` pass before the RDB is copied and signature-checked locally.
It then scales data
Deployments and StatefulSets to zero and waits for their Pods to terminate.
Accessible external hostPath PV directories receive verified quiesced tar
archives. Dynamic PVCs, Docker-VM-local hostPaths, etcd, Kubernetes Secrets,
and the rest of Docker Desktop's VM state are protected together by the full
Docker.raw rollback clone.

The host-only backup directory defaults to
`~/Library/Application Support/BuddyStudy/KubernetesRetirementBackups` and is
created with mode `0700` outside Docker Desktop's Data directory. Symlinked
settings, data, and backup paths are rejected; a declared external `/Users` or
`/Volumes` hostPath that is missing or symlinked also blocks apply. FileVault
must be on and the source and backup must be on the same APFS filesystem. The
preflight requires a 12 GiB base reserve plus twice the measured external
hostPath size so plaintext staging and ciphertext can safely coexist. The
helper seals manifests, logical dumps, settings, workload state, and
external hostPath archives as an OpenSSL AES-256-CBC/PBKDF2 bundle with a
separate HMAC-SHA256. It then stops Docker Desktop gracefully and creates a
byte/HMAC-verified APFS copy-on-write `Docker.raw.apfs-clone`. Backups are never
uploaded as Actions artifacts and secret or data payloads are never written to
the job log.

Docker documents the Desktop UI as the supported place to enable or disable
Kubernetes. Because the deploy-only runner has no reliable interactive UI,
this workflow uses a narrowly audited fallback: while Desktop is stopped it
backs up the entire settings store, changes only the one existing boolean
`KubernetesEnabled`/`kubernetesEnabled` key to `false` with an atomic replace,
then restarts Desktop. It verifies that setting and requires zero *running*
Kubernetes-labelled/control-plane containers while preserving every
non-Kubernetes container identity. It does not remove stopped Kubernetes
metadata.

If anything fails after the disabled Desktop has started, rollback gracefully
stops Desktop, preserves that failed-current Docker.raw, restores the verified
APFS clone to the exact original Docker.raw path, restores the byte-for-byte
settings file, restarts Desktop, and restores recorded replicas and CronJob
suspend values. A failed rollback keeps every recovery copy and reports only
safe paths/status. Docker Desktop itself is never force-killed.

Keep `MACBOOKAIR_K8S_RETIREMENT_BACKUP_KEY` available for recovery. Before any
workload is changed, apply stores the same value in the Air login Keychain under
service `BuddyStudy MacBook Air Kubernetes Retirement Backup` and account
`buddystudy-kubernetes-retirement`, then reads it back and compares it without
logging command output. A mismatch blocks apply. Retain both the repository
secret and this host-local Keychain copy. To inspect the encrypted bundle on
the Air without logging content, recover/export the key only in the local shell
and run:

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 300000 \
  -pass env:MACBOOKAIR_K8S_RETIREMENT_BACKUP_KEY \
  -in retirement-backup.tar.enc | tar -tf -
```

This workflow never resets or purges Docker Desktop, deletes a namespace, PVC,
PV, Docker volume, container, or network, or runs a prune. It performs no HTTP,
database-health, or container-health gate. The logical dump and RDB commands
are backup operations after writers stop, not readiness checks.

The read-only run has a 12-minute helper deadline inside a 15-minute Actions
watchdog. Kubernetes inventory uses three bounded bulk-list calls, every
`kubectl` request has a 20-second API timeout, and all external hostPaths are
measured by one bounded `du` process. Progress output contains only a fixed
non-sensitive stage label and elapsed seconds—never resource names, paths,
arguments, environment values, command output, or counts. Workflow steps use
`exec` so cancellation reaches the helper directly; each child command or
pipeline runs in its own process group, which is killed and reaped on timeout
or interruption so descendants cannot keep an output pipe open. The 12-minute
deadline is installed only for the standalone preflight command and is removed
before exit; it is never shared with apply or rollback.

The apply job allows six hours while each logical dump, hostPath archive,
bundle seal, and APFS clone has a shorter bounded timeout, leaving rollback
time. SIGTERM and SIGINT request the guarded rollback path. Runtime image,
settings, and workload restoration always run before any failed plaintext
staging is processed. Only after rollback does the helper make a best-effort
failed-bundle seal under a three-minute outer bound and 120-second seal bound;
an unsuccessful seal gets a separately bounded 30-second private-staging
cleanup attempt. These backup-finalization results cannot mask or delay the
reported rollback result.

### Legacy TestZone component log migration

Copy `migrate-testzone-component-logging.yml` and
`scripts/migrate_testzone_component_logging.py` into the deploy repository.
Run **Migrate TestZone Component Logging** first with its default
`apply=false`. This is a read-only preflight over exactly
`buddystudy-testzone-postgres` and `buddystudy-testzone-redis`; it keeps each
inspect document in memory, validates the TestZone-managed label, an approved
legacy image tag, `json-file` or an already-compliant `local` driver, and the
single Docker volume at the component's exact data destination. The safe
summary reports the actual named or anonymous Docker volume identities without
environment variables, commands, entrypoints, or other potentially secret
configuration. Auto-removing containers and any run-ID-suffixed backup left by
an earlier attempt are rejected before mutation.

After reviewing that preflight, explicitly run with `apply=true`. The workflow
shares the `deploy-macbookair-testzone` concurrency group, stops each affected
container with a 60-second grace period, and reuses its immutable inspected
image ID, actual volume identity, container configuration, networks, ports,
resources, restart policy, labels, environment, command, and entrypoint. Only
the logging policy changes to Docker `local` at 10 MiB times three compressed
files. Both replacements must accept their submitted configuration before
run-ID-suffixed backups are removed without `-v`; a create, configuration, or
start failure restores the original containers. The accepted migration causes
a brief component restart when the original was running and preserves a stopped
original as stopped. Removing a retired backup also removes its old
`json-file` history, which Docker cannot recover unless it was copied or
forwarded beforehand. The database/Redis volumes are never copied, removed, or
pruned. Actions performs no HTTP, database, or container-health gate.

EC2 does not run Loki or Grafana. It runs only `buddystudy-promtail` when
`REMOTE_LOKI_PUSH_URL` is configured. Grafana and Loki are owned exclusively
by `deploy-macbookair-monitoring.yml`; do not add an EC2-local fallback.

Prometheus and Redis exporter containers are not part of this production
monitoring profile.

## RedisStreamScope Deploy

Copy `deploy-macbookair-redisstreamscope.yml` and
`deploy-macbookair-monitoring-routing.yml` into the deploy repository. Run
**Deploy RedisStreamScope on MacBook Air** first, then run
**Deploy BuddyStudy Monitoring Routes on MacBook Air** to publish
`redis.lowfidev.cloud`.

The runtime workflow pulls the immutable RedisStreamScope GHCR digest, stores
SQLite and connection configuration in the
`buddystudy-redisstreamscope-data` Docker volume, and configures the production
Redis cluster from repository secrets. The application container has no host
port. The existing `buddystudy-api-dashboard` Nginx container owns the
`127.0.0.1:3002` listener and proxies to RedisStreamScope on the shared private
monitoring network. RedisStreamScope keeps its own session authentication.
Routingflare maps the public hostname to that loopback listener.

Grafana dashboard provisioning is file-based, so dashboards are restored on
container recreation:

- `BuddyStudy Log Search`
- `BuddyStudy API Performance`

The MacBook Air workflow downloads dashboard JSON from this repository's
`docs/observability/` directory and mounts them into Grafana provisioning. This
keeps Grafana UI state from being the source of truth for log dashboards.

Recommended flow:

1. Run **Deploy BuddyStudy Monitoring on MacBook Air**.
2. Confirm MacBook Air Loki is reachable from EC2.
3. Set `REMOTE_LOKI_PUSH_URL` in the deploy repository variables.
4. Run **Deploy BuddyStudy Backend**. This starts or refreshes EC2 promtail.

## Manual Deploy

Run the `Deploy BuddyStudy Backend` workflow and provide the backend image ref,
for example:

```text
ghcr.io/ghkdqhrbals/buddystudy-backend:latest
```

The backend image must be built on a GitHub-hosted runner and pushed to GHCR
before this workflow runs. The self-hosted EC2 runner only pulls the image and
runs containers; it must not compile backend code or build Docker images.

The deploy process uses Docker Swarm rolling updates:

1. The workflow submits the immutable image to `buddystudy_backend`.
2. Swarm starts the replacement task before stopping the current task.
3. The image health check calls only
   `/api/v1/health/dependencies`; `failure_action: rollback` restores the
   previous task when the replacement does not become healthy. The
   post-readiness monitor window is five seconds.
4. The workflow waits for Swarm `UpdateStatus=completed`, `1/1` replicas, and a
   running task whose immutable image matches the requested release. A paused
   or rolled-back update fails the workflow and the deployment notification.
5. Nginx keeps the fixed `buddystudy_backend:8080` upstream, so routine updates
   do not rewrite or race the proxy configuration.

For the first migration only, run with `promote_swarm=false`, inspect the staged
task and logs, then rerun the same image with `promote_swarm=true`. This switches
Nginx once and removes the former A/B containers without resubmitting the
stack, so the inspected staged task is the task that receives traffic. A single
Swarm node provides zero-downtime application replacement but does not provide
host failover.

Use `Inspect BuddyStudy Backend Swarm` after the image startup grace period.
It reports only the service image, replica count, update state, and task
history. It does not call the application or expose the service environment.

Only one scheduler leader is active during overlap windows. MySQL advisory lock is used so only one running backend instance processes scheduled question dispatch at a time.

Backend deployments preserve the running MySQL and Redis containers. Redis is
not recreated during an application rollout because a Redis restart would make
all backend dependency health checks fail simultaneously. Redis runtime changes
must use an infrastructure workflow.

The workflow uses Let's Encrypt with the `tls-alpn-01` challenge, so only port `443` needs to be public. If certificate issuance fails, a temporary self-signed certificate keeps the service reachable for debugging.

GitHub Actions must not call backend `/health` or readiness endpoints, must not
inspect Docker `Health.Status`, must not use indirect container health gates
such as `docker compose up --wait` or `docker compose wait`, and must not call the Health Monitor Worker `/check` endpoint. It waits on Swarm's control-plane
rollout state instead. Swarm evaluates the image health check as the platform
rollout policy; only a completed rollout with the requested image and replica
count is reported as successful. Runtime server-down alerts remain handled by
Grafana alerting. The Cloudflare Worker remains available for explicit
diagnostics, but its production Cron check is disabled.

Backend scheduler failures are emitted as `ERROR` logs with the throwable and
run identifiers. Promtail stores the complete stack as one Loki event, and
Grafana alone sends the Slack notification and independently calls the private,
HMAC-signed incident receiver. The backend application does not receive Slack,
GitHub, or Codex credentials. The incident receiver dispatches only a bounded,
redacted `codex-incident-autofix` payload; a separate GitHub-hosted workflow may
open a verified Draft PR but never merges or deploys it. The template passes
`MONITORING_SCHEDULER_READINESS_ENABLED`,
`MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES`,
`MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES`, and
`MONITORING_SCHEDULER_MONITORED_JOBS` into the backend so Docker deployments
use the same scheduler readiness policy as Kubernetes. Only frequent managed
jobs belong in this 15-minute readiness list. Admin analytics is refreshed only
by an explicit authenticated operator request and is not a managed batch job.
Grafana alerting owns continuous server-down detection. The Cloudflare Worker
scheduled check is disabled in production to avoid periodic KV writes.

Slack uses separate app webhooks for separate sender identities:

- `GRAFANA_SLACK_WEBHOOK_URL` belongs to the Grafana Slack app, whose app name
  and icon are configured as Grafana.
- `DEPLOY_SLACK_WEBHOOK_URL` belongs to the BuddyStudy Deploy Slack app, whose
  app name and icon are configured for deployments.
- `SLACK_WEBHOOK_URL` remains a temporary fallback for both workflows while the
  dedicated app webhooks are being provisioned. Slack app name and icon are
  properties of the app behind an Incoming Webhook, so a single webhook cannot
  reliably present two different sender identities.

`api.ghkdqhrbals.org` must resolve to the EC2 host for trusted certificate issuance.

## Backup restore

```sh
gunzip -c /absolute/path/buddystudy-<timestamp>.sql.gz | docker run --rm -i \
  -e MYSQL_PWD="<mysql-password>" \
  --network buddystudy-net \
  mysql:8.4 \
  mysql -h buddystudy-db -u buddystudy buddystudy
```

The PostgreSQL-to-MySQL cutover is not an in-place container replacement.
Follow [`MYSQL_MIGRATION.md`](../MYSQL_MIGRATION.md), retain the old
PostgreSQL volume for rollback, and update `buddystudy/prod/mysql` only after
row-count and API validation.
