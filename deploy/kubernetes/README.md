# BuddyStudy Kubernetes Deployment

This directory contains the Kubernetes manifests for running BuddyStudy on a
small ARM64 Kubernetes node, such as a Mac mini/MacBook Kubernetes target.

For always-on server usage, prefer the k3s deployment path in `deploy/k3s`.
Docker Desktop Kubernetes is only suitable for local development and can pause
or hang its local API server when used as a long-running server runtime.

## Layout

- `namespace.yaml`: shared namespace.
- `config/`: non-secret environment configuration.
- `secrets/`: placeholder Kubernetes secrets. These are not applied by kustomize.
- `mysql/`: MySQL StatefulSet, service, hostPath PV/PVC, and init script.
- `redis/`: single-node Redis StatefulSet, service, and hostPath PV/PVC.
- `libretranslate/`: LibreTranslate Deployment, service, and model PVC.
- `backend/`: BuddyStudy backend Deployment and service.
- `admin-frontend/`: admin web frontend Deployment and service.
- `backup/`: daily MySQL dump CronJob.

## Apply

```sh
kubectl apply -k deploy/kubernetes
```

Or apply the rendered single-file snapshot:

```sh
kubectl apply -f deploy/kubernetes/deploy.yaml
```

To deploy from a remote host after copying this repository:

```sh
cd /path/to/study-mate
kubectl apply -k deploy/kubernetes
kubectl -n buddystudy rollout status deploy/buddystudy-backend
kubectl -n buddystudy rollout status deploy/buddystudy-admin-frontend
```

To copy and apply through the Tailscale SSH target requested for this project:

```sh
deploy/kubernetes/remote-apply.sh gyuminhwangbo@gyumin-macbookair
```

## Secrets

`secrets/backend-secret.yaml` contains development placeholders only. It is
intentionally not included in `kustomization.yaml`, because applying placeholder
secrets would overwrite live cluster credentials. Create or patch
`buddystudy-backend-secret` separately before rollout. Required keys include:

- `BACKEND_MASTER_KEY`
- `AUTH_JWT_SECRET`
- `ADMIN_PASSWORD`
- `OPENAI_API_KEY`
- APNs values when push delivery is required
- SMTP values when email login is required
- `SLACK_WEBHOOK_URL` for production scheduler failure alerts

`config/backend-config.yaml` contains non-secret monitoring values such as
`MONITORING_ADMIN_BASE_URL`, which must be an HTTPS admin UI URL for scheduler
Slack alert links.

Production backend pods fail fast when `SLACK_WEBHOOK_URL` is empty or
`MONITORING_ADMIN_BASE_URL` is not an HTTPS URL while the scheduler is enabled.
This prevents scheduler failure alerts from being silently disabled or shipped
without useful admin links. They also fail fast when monitored scheduler job
names do not match registered backend jobs or when scheduler monitoring
timeouts, stale thresholds, or startup grace windows are outside the supported
production ranges.

## Health Probes and Alerts

Backend Kubernetes readiness probes use:

```http
GET /api/v1/health/dependencies
```

This endpoint checks only hard serving dependencies such as MySQL and
Redis. Do not point Kubernetes readiness probes at
`/api/v1/health/readiness`, because that endpoint also checks scheduler
freshness and is meant for external alerting.

Server-down Slack alerts are handled by the Cloudflare Worker in
`deploy/cloudflare-health-monitor`. It checks:

```http
GET /api/v1/health/readiness
```

That external readiness endpoint includes scheduler freshness, disabled job,
stale job, failed job, and stuck job details so Slack alerts carry the
operational cause without taking healthy API pods out of service.

## Local Persistent Data

The Mac Kubernetes target stores state on the host:

- MySQL: `/Users/gyuminhwangbo/data/buddystudy/db/mysql`
- Redis: `/Users/gyuminhwangbo/data/buddystudy/redis/standalone`

The PV reclaim policy is `Retain`; deleting Kubernetes workloads must not delete
these host directories.

## External Access

The Mac Kubernetes target exposes fixed NodePorts. Do not use persistent
`kubectl port-forward` LaunchAgents for DB/Redis access; they hide the actual
network path and can conflict with Kubernetes services after restarts.

- Backend API: `localhost:30080`
- MySQL: `localhost:30432`
- Redis: `localhost:6379`

Cloudflare should be used in two different modes:

- HTTP services use hostname ingress.
- DB/Redis administration should use a WARP private network route to the
  MacBook Air node IP.

`deploy/cloudflared/lowfidev-config.yaml` maps public HTTP services and keeps
compatibility TCP hostnames:

- `api.lowfidev.cloud` -> `http://localhost:30080`
- `db.lowfidev.cloud` -> `tcp://localhost:30432`
- Redis is exposed through a node `hostPort`, not a proxy service.

For normal DB/Redis access, run `deploy/cloudflared/setup-private-route.sh` or
use Tailscale, then connect to:

- MySQL: `<macbook-air-lan-ip-or-tailscale-ip>:30432`
- Redis: `<macbook-air-lan-ip-or-tailscale-ip>:6379`

The `buddystudy-redis-proxy` deployment is intentionally removed because it
introduced a second routing layer and could fail when Redis metadata changed.
The backend uses Spring Data Redis Streams directly, so the Redis Stream
Coordinator is not part of this deployment.

Cloudflare Tunnel TCP hostnames require clients to connect through
`cloudflared access tcp` unless Cloudflare Spectrum is configured separately.
Prefer WARP private routing over Spectrum for this project because DB/Redis
should not be public Internet services.

If GHCR packages are private, create an image pull secret and add it to the
backend/admin frontend Deployments:

```sh
kubectl -n buddystudy create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<github-token>
```
