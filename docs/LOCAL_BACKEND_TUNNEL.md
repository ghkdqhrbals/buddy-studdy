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

## 2. Open or verify the Cloudflare tunnel

On the Mac Kubernetes target, `~/.cloudflared/config.yaml` should match
`deploy/cloudflared/lowfidev-config.yaml` and define these ingress routes:

```text
api.lowfidev.cloud   -> http://localhost:30080
db.lowfidev.cloud    -> tcp://localhost:30432
redis.lowfidev.cloud -> tcp://localhost:30379
```

Run the named tunnel directly from the Mac Kubernetes target when it is not
already managed by the local Cloudflare connector:

```sh
cloudflared tunnel --config ~/.cloudflared/config.yaml run
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

## Notes

- Do not use `localhost` or `127.0.0.1` in the iPhone app. Those point to the iPhone, not the Mac.
- Do not use a LaunchAgent for Kubernetes DB/Redis port forwarding. The Kubernetes services expose fixed NodePorts, and LaunchAgent port-forwarding can hide the actual network path.
- The tunnel can be healthy while the backend is down. In that case Cloudflare returns 502 until `localhost:30080` is running.
- Keep production debugging off before App Store or TestFlight verification.
