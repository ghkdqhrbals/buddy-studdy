# BuddyStuddy k3s Deployment

This deployment path is for running BuddyStuddy as an always-on server on a
Linux host or Linux VM. Do not use Docker Desktop Kubernetes for this runtime.

## Target Shape

```text
Linux host or VM
  systemd
    k3s
      MySQL
      Redis
      BuddyStuddy backend
      Admin frontend
      LibreTranslate
      MySQL backup CronJob
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
  - `30432/tcp`: MySQL NodePort
  - `6379/tcp`: Redis host port
- Images must be available in GHCR, or configure `ghcr-pull-secret`.

## Persistent Data

k3s stores BuddyStuddy data on the host:

- MySQL: `/var/lib/buddystudy/mysql`
- Redis: `/var/lib/buddystudy/redis/standalone`
- MySQL backups: `/var/lib/buddystudy/backups/mysql`

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
- Redis administration via Tailscale/private network -> `<host-ip>:6379`

Do not expose MySQL or Redis publicly without a private network or
additional authentication layer.

## Migration From Docker Desktop Kubernetes

1. Stop writes from the app/backend.
2. Export and transform the old PostgreSQL data according to
   [`MYSQL_MIGRATION.md`](../MYSQL_MIGRATION.md). PostgreSQL dumps cannot be
   restored directly with the MySQL client.
3. Install k3s and apply manifests on the Linux host.
4. Import the transformed rows into MySQL, validate row counts and foreign-key
   integrity, then set each table's next `AUTO_INCREMENT` value.
5. Redis is cache/stream runtime state. Recreate the standalone Redis instance
   rather than copying Docker Desktop runtime metadata.
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
- Redis health:
  ```sh
  kubectl -n buddystudy exec buddystudy-redis-0 -- \
    sh -c 'redis-cli -a "$REDIS_PASSWORD" ping'
  ```
