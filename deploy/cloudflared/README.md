# BuddyStudy Cloudflare Tunnel

This tunnel exposes BuddyStudy services running on the MacBook Air Kubernetes
node.

## Recommended Access Model

Use different Cloudflare features for different traffic types:

- Public HTTP services: hostname ingress in `lowfidev-config.yaml`
- Private TCP administration: WARP private network route to the MacBook Air node
- Direct public DB/Redis TCP: avoid unless Cloudflare Spectrum is explicitly
  required later

This keeps API/Grafana URLs simple while avoiding public DB/Redis exposure.

## Public Hostnames

`lowfidev-config.yaml` keeps these HTTP routes:

- `api.lowfidev.cloud` -> `localhost:30080`
- `coordinator.lowfidev.cloud` -> `localhost:8080`
- `grafana.lowfidev.cloud` -> `localhost:3000`
- `ssh.lowfidev.cloud` -> local SSH through Cloudflare Access

The config also keeps compatibility TCP hostnames:

- `db.lowfidev.cloud` -> `localhost:5432`
- `redis.lowfidev.cloud` -> `localhost:6379`

Those TCP hostnames still require a local client command such as
`cloudflared access tcp`. For regular administration, prefer the WARP private
route below.

## Private DB/Redis Access Through WARP

Run this once on a machine authenticated to the Cloudflare account:

```sh
deploy/cloudflared/setup-private-route.sh
```

The script detects the MacBook Air LAN IP and registers a `/32` private route
for tunnel `24c83c3f-3c20-402f-a9ca-247ca8d25fbb`.

After the route is active, enable Cloudflare WARP on the client machine and
connect directly to the MacBook Air node IP:

```txt
PostgreSQL host: <macbook-air-lan-ip>
PostgreSQL port: 5432
Database: buddystudy
User: buddystudy

Redis host: <macbook-air-lan-ip>
Redis port: 6379
```

This is the closest equivalent to the old EC2 public-IP workflow without
opening DB/Redis to the public Internet.

## Compatibility TCP Commands

If WARP private routing is unavailable, keep using local Access TCP proxies:

```sh
cloudflared access tcp --hostname db.lowfidev.cloud --url localhost:15432
cloudflared access tcp --hostname redis.lowfidev.cloud --url localhost:16379
```

Then connect local clients to `localhost:15432` or `localhost:16379`.

## Troubleshooting

Only one cloudflared process should serve tunnel
`24c83c3f-3c20-402f-a9ca-247ca8d25fbb`.

If `api.lowfidev.cloud/health` returns `404` while
`http://localhost:30080/health` returns `200` on the MacBook Air, check for a
second TunnelBar-managed connector:

```sh
ps aux | grep '[c]loudflared'
```

The managed process should look like:

```txt
cloudflared tunnel --config /Users/gyuminhwangbo/.cloudflared/config.yaml run 24c83c3f-3c20-402f-a9ca-247ca8d25fbb
```

If another TunnelBar config uses the same tunnel and maps BuddyStudy hostnames
to a temporary local HTTP port, stop that process or disable that TunnelBar
profile. Otherwise Cloudflare can route requests to the wrong connector.
