# BuddyStuddy k3s Deployment

This deployment path is for running BuddyStuddy as an always-on server on a
Linux host or Linux VM. Do not use Docker Desktop Kubernetes for this runtime.

## Target Shape

```text
Linux host or VM
  systemd
    k3s
      PostgreSQL
      Redis Cluster + Redis Cluster proxy
      BuddyStuddy backend
      Admin frontend
      LibreTranslate
      PostgreSQL backup CronJob
```

## Why k3s

Docker Desktop Kubernetes is a local development runtime. Its Resource Saver,
GUI lifecycle, VM pause/resume, and local API proxy can break long-running
`kubectl port-forward` sessions. k3s runs as a Linux systemd service and is a
better fit for a small always-on single-node server.

## Requirements

- Linux host or Linux VM. macOS cannot run k3s directly.
- 2 vCPU / 4 GiB memory minimum recommended.
- Ports:
  - `30080/tcp`: backend NodePort
  - `30432/tcp`: PostgreSQL NodePort
  - `30379/tcp`: Redis Cluster proxy NodePort
- Images must be available in GHCR, or configure `ghcr-pull-secret`.

## Persistent Data

k3s stores BuddyStuddy data on the host:

- PostgreSQL: `/var/lib/buddystudy/postgres`
- Redis:
  - `/var/lib/buddystudy/redis/redis-0`
  - `/var/lib/buddystudy/redis/redis-1`
  - `/var/lib/buddystudy/redis/redis-2`
- PostgreSQL backups: `/var/lib/buddystudy/backups/postgres`

The Kubernetes PV reclaim policy is `Retain`. Do not delete these host
directories unless data loss is intended.

## Install k3s

Run on the Linux host:

```sh
sudo deploy/k3s/scripts/install-k3s.sh
```

The installer disables bundled Traefik because Cloudflared currently routes to
NodePorts directly.

## Apply

Create or patch `buddystudy-backend-secret` before applying app workloads.
Then run:

```sh
deploy/k3s/scripts/apply.sh
```

Remote apply:

```sh
deploy/k3s/scripts/remote-apply.sh user@linux-host
```

Status:

```sh
deploy/k3s/scripts/status.sh
```

## Cloudflared

Cloudflared should run on the same Linux host and route:

- `api.lowfidev.cloud` -> `http://localhost:30080`
- DB administration via Tailscale/private network -> `<host-ip>:30432`
- Redis administration via Tailscale/private network -> `<host-ip>:30379`

Do not expose PostgreSQL or Redis publicly without a private network or
additional authentication layer.

## Migration From Docker Desktop Kubernetes

1. Stop writes from the app/backend.
2. Dump PostgreSQL from the old runtime:
   ```sh
   pg_dump -h <old-host> -p 5432 -U buddystudy -d buddystudy -Fc > buddystudy.dump
   pg_dump -h <old-host> -p 5432 -U buddystudy -d buddystudy_aggregation -Fc > buddystudy_aggregation.dump
   ```
3. Install k3s and apply manifests on the Linux host.
4. Restore into the new PostgreSQL:
   ```sh
   pg_restore -h <new-host> -p 30432 -U buddystudy -d buddystudy --clean --if-exists buddystudy.dump
   pg_restore -h <new-host> -p 30432 -U buddystudy -d buddystudy_aggregation --clean --if-exists buddystudy_aggregation.dump
   ```
5. Redis is cache/stream runtime state. Recreate Redis Cluster rather than
   copying Docker Desktop Redis node metadata.
6. Switch Cloudflared/DNS to the k3s host.
7. Verify `/health`, DB, Redis, scheduler, push, and admin dashboard.

## Operational Notes

- k3s restart:
  ```sh
  sudo systemctl restart k3s
  ```
- k3s logs:
  ```sh
  sudo journalctl -u k3s -f
  ```
- Redis Cluster health:
  ```sh
  kubectl -n buddystudy exec buddystudy-redis-0 -- \
    sh -c 'redis-cli -a "$REDIS_PASSWORD" cluster info'
  ```
