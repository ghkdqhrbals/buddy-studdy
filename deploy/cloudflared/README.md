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
- `monitoring.lowfidev.cloud` -> `localhost:3000` (custom operational UI)
- `grafana.lowfidev.cloud` -> `localhost:3001` (standalone Grafana)
- `ssh.lowfidev.cloud` -> local SSH through Cloudflare Access

The config also keeps compatibility TCP hostnames:

- `db.lowfidev.cloud` -> `localhost:30432`
- `redis.lowfidev.cloud` -> `localhost:30379`

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
PostgreSQL port: 30432
Database: buddystudy
User: buddystudy

Redis host: <macbook-air-lan-ip>
Redis port: 30379
```

This is the closest equivalent to the old EC2 public-IP workflow without
opening DB/Redis to the public Internet.

The production MacBook Air uses Routingflare for the two monitoring HTTP
routes. Apply them with the dedicated
`Deploy BuddyStudy Monitoring Routes on MacBook Air` workflow; do not attach
route changes to backend or TestZone deployment jobs.

The routing workflow updates Routingflare, then restarts the menu-bar app
through macOS Launch Services before starting the named-tunnel connector. The
current proxy filters invalid hop-by-hop response headers such as
`Transfer-Encoding: Identity`. Launch Services also keeps the app and its
`cloudflared` child independent from the GitHub Actions runner, so the runner's
orphan-process cleanup cannot take the public routes offline.

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
