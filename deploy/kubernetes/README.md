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
- `postgres/`: PostgreSQL StatefulSet, service, hostPath PV/PVC, and init script.
- `redis/`: Redis Cluster StatefulSet, services, hostPath PV/PVCs, and cluster init Job.
- `libretranslate/`: LibreTranslate Deployment, service, and model PVC.
- `backend/`: BuddyStudy backend Deployment and service.
- `admin-frontend/`: admin web frontend Deployment and service.
- `backup/`: daily PostgreSQL dump CronJob.

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
- Redis Stream Coordinator credentials when streams are enabled

## Local Persistent Data

The Mac Kubernetes target stores state on the host:

- PostgreSQL: `/Users/gyuminhwangbo/data/buddystudy/db/postgres`
- Redis Cluster:
  - `/Users/gyuminhwangbo/data/buddystudy/redis/redis-0`
  - `/Users/gyuminhwangbo/data/buddystudy/redis/redis-1`
  - `/Users/gyuminhwangbo/data/buddystudy/redis/redis-2`

The PV reclaim policy is `Retain`; deleting Kubernetes workloads must not delete
these host directories.

## External Access

The Mac Kubernetes target exposes fixed local ports. Docker Desktop Kubernetes
does not reliably bind `hostPort` to the macOS host network, so install the
launchd port-forward agents after applying the manifests:

```sh
deploy/kubernetes/install-local-port-forwards.sh
```

This creates persistent user LaunchAgents bound to `127.0.0.1` and the
machine's Tailscale IPv4 address when Tailscale is installed. Override with
`FORWARD_ADDRESS=...` only when you intentionally want a different bind address.

The agents forward:

- Backend API: `localhost:30080`
- PostgreSQL: `<bind-address>:5432` -> `svc/buddystudy-postgres:5432`
- Redis Cluster proxy: `<bind-address>:6379` -> `svc/buddystudy-redis-external:6379`

Kubernetes NodePort fallbacks are still available:

- PostgreSQL: `localhost:30432`
- Redis Cluster proxy: `localhost:30379`

Cloudflare should be used in two different modes:

- HTTP services use hostname ingress.
- DB/Redis administration should use a WARP private network route to the
  MacBook Air node IP.

`deploy/cloudflared/lowfidev-config.yaml` maps public HTTP services and keeps
compatibility TCP hostnames:

- `api.lowfidev.cloud` -> `http://localhost:30080`
- `db.lowfidev.cloud` -> `tcp://localhost:5432`
- `redis.lowfidev.cloud` -> `tcp://localhost:6379`

For normal DB/Redis access, run `deploy/cloudflared/setup-private-route.sh` or
use Tailscale, then connect to:

- PostgreSQL: `<macbook-air-lan-ip-or-tailscale-ip>:5432`
- Redis Cluster proxy: `<macbook-air-lan-ip-or-tailscale-ip>:6379`

The Redis tunnel points at `buddystudy-redis-external`, a single Redis Cluster
proxy endpoint. Do not point the tunnel directly at Redis Cluster nodes; direct
node exposure can return internal cluster addresses in redirects.

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
