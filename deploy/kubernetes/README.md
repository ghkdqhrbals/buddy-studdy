# BuddyStudy Kubernetes Deployment

This directory contains the Kubernetes manifests for running BuddyStudy on a
small ARM64 Kubernetes node, such as a Mac mini/MacBook Kubernetes target.

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
kubectl -n buddystuddy rollout status deploy/buddystuddy-backend
kubectl -n buddystuddy rollout status deploy/buddystuddy-admin-frontend
```

To copy and apply through the Tailscale SSH target requested for this project:

```sh
deploy/kubernetes/remote-apply.sh gyuminhwangbo@gyumin-macbookair
```

## Secrets

`secrets/backend-secret.yaml` contains development placeholders only. It is
intentionally not included in `kustomization.yaml`, because applying placeholder
secrets would overwrite live cluster credentials. Create or patch
`buddystuddy-backend-secret` separately before rollout. Required keys include:

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

## External Tunnel Ports

The Mac Kubernetes target exposes fixed NodePorts for Cloudflare Tunnel:

- Backend API: `localhost:30080`
- PostgreSQL: `localhost:30432`
- Redis Cluster entry service: `localhost:30379`

`deploy/cloudflared/lowfidev-config.yaml` maps:

- `api.lowfidev.cloud` -> `http://localhost:30080`
- `db.lowfidev.cloud` -> `tcp://localhost:30432`
- `redis.lowfidev.cloud` -> `tcp://localhost:30379`

Cloudflare Tunnel TCP hostnames require clients to connect through
`cloudflared access tcp` unless Cloudflare Spectrum is configured separately.

If GHCR packages are private, create an image pull secret and add it to the
backend/admin frontend Deployments:

```sh
kubectl -n buddystuddy create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<github-token>
```
