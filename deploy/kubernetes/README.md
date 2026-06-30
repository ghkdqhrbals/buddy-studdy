# BuddyStuddy Kubernetes Deployment

This directory contains the Kubernetes manifests for running BuddyStuddy on a
small ARM64 Kubernetes node, such as a Mac mini/MacBook Kubernetes target.

## Layout

- `namespace.yaml`: shared namespace.
- `config/`: non-secret environment configuration.
- `secrets/`: placeholder Kubernetes secrets. Replace values before production use.
- `postgres/`: PostgreSQL StatefulSet, service, PVC, and init script.
- `redis/`: Redis Deployment and service.
- `libretranslate/`: LibreTranslate Deployment, service, and model PVC.
- `backend/`: BuddyStuddy backend Deployment and service.
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

`secrets/backend-secret.yaml` contains development placeholders so the
manifests are self-contained. Before a real deployment, replace at least:

- `BACKEND_MASTER_KEY`
- `AUTH_JWT_SECRET`
- `ADMIN_PASSWORD`
- `OPENAI_API_KEY`
- APNs values when push delivery is required
- SMTP values when email login is required
- Redis Stream Coordinator credentials when streams are enabled

If GHCR packages are private, create an image pull secret and add it to the
backend/admin frontend Deployments:

```sh
kubectl -n buddystuddy create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<github-token>
```
