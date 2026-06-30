# Local Backend Tunnel

Use this when you want the iOS app or local tools to reach the BuddyStudy
development stack running on the Mac Kubernetes target.

## 1. Run the local stack

```sh
deploy/kubernetes/remote-apply.sh gyuminhwangbo@gyumin-macbookair
```

The Kubernetes services expose fixed local NodePorts on the Mac:

```text
Backend API: localhost:30080
PostgreSQL:   localhost:30432
Redis:        localhost:30379
```

## 2. Open a Cloudflare tunnel

From the repository root:

```sh
scripts/start-local-api-tunnel.sh
```

On the Mac Kubernetes target, `~/.cloudflared/config.yaml` should match
`deploy/cloudflared/lowfidev-config.yaml` and define these ingress routes:

```text
api.lowfidev.cloud   -> http://localhost:30080
db.lowfidev.cloud    -> tcp://localhost:30432
redis.lowfidev.cloud -> tcp://localhost:30379
```

If no named tunnel config exists, Cloudflare prints a temporary HTTPS URL like:

```text
https://example.trycloudflare.com
```

## 3. Point the iOS app at the tunnel

In BuddyStudy on iPhone:

1. Open Settings.
2. Enable Debugging Mode.
3. Paste the Cloudflare HTTPS URL into Debug API URL. For the shared dev stack, use `https://api.lowfidev.cloud`.
4. Tap Save.

After saving, every backend API request made by the app uses the debug URL until Debugging Mode is turned off.

## TCP access

Cloudflare Tunnel TCP hostnames are not direct public TCP sockets. Use
`cloudflared access tcp` locally, then connect your client to the local port:

```sh
cloudflared access tcp --hostname db.lowfidev.cloud --url localhost:15432
psql "postgresql://<user>:<password>@localhost:15432/buddystudy"
```

```sh
cloudflared access tcp --hostname redis.lowfidev.cloud --url localhost:16379
redis-cli -p 16379 -a "<password>"
```

## Auto Start On Login

The persistent local tunnel is registered as a user LaunchAgent:

```text
~/Library/LaunchAgents/com.buddystudy.local-api-tunnel.plist
```

It runs:

```text
scripts/start-local-api-tunnel.sh
```

Logs are written to:

```text
~/Library/Logs/BuddyStudy/local-api-tunnel.log
~/Library/Logs/BuddyStudy/local-api-tunnel.err
```

Useful commands:

```sh
launchctl print "gui/$(id -u)/com.buddystudy.local-api-tunnel"
launchctl kickstart -k "gui/$(id -u)/com.buddystudy.local-api-tunnel"
```

## Notes

- Do not use `localhost` or `127.0.0.1` in the iPhone app. Those point to the iPhone, not the Mac.
- Quick tunnel URLs change each time the tunnel starts. Named tunnel URLs such as `https://api.lowfidev.cloud` do not.
- The tunnel can be healthy while the backend is down. In that case Cloudflare returns 502 until `localhost:30080` is running.
- Keep production debugging off before App Store or TestFlight verification.
