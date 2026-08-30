# Local Backend Tunnel

Use this when you want the iOS app or local tools to reach the BuddyStudy
development stack running on the Mac Kubernetes target.

For an already-running Docker development stack, keep its existing `backend`
Compose network, database, and Redis. The API origin is
`http://127.0.0.1:8080`, not the Kubernetes NodePort below. The `dev` profile
imports the configured AWS development secret; a tunnel issue does not require
another database, Redis instance, or a different Spring profile.

## 1. Run the local stack

```sh
deploy/kubernetes/remote-apply.sh gyuminhwangbo@gyumin-macbookair
```

The Kubernetes services expose fixed local NodePorts on the Mac:

```text
Backend API: localhost:30080
MySQL:       localhost:30432
Redis:        localhost:30379
```

## 2. Open or verify the Cloudflare tunnel

On the Mac Kubernetes target, `~/.cloudflared/config.yaml` should match
`deploy/cloudflared/lowfidev-config.yaml` and define these ingress routes:

```text
lowfidev.cloud       -> http://localhost:30080
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
3. Paste the Cloudflare HTTPS URL into Debug API URL. For the shared dev stack, use `https://lowfidev.cloud`.
4. Tap Save.

After saving, every backend API request made by the app uses the debug URL until Debugging Mode is turned off.

## Realtime voice requires WebSocket forwarding

Voice calls use an SDP `POST /api/v1/voice-tutor/sessions/{id}/webrtc` and a
separate WebSocket `GET /api/v1/voice-tutor/sessions/{id}/control`. A successful
SDP response or ordinary API request does not verify the control connection:
the authenticated control request must successfully upgrade to WebSocket.

Cloudflare Tunnel supports WebSockets, but every intermediate origin proxy
must also support the upgrade and bidirectional forwarding. In particular,
`Upgrade: websocket`, `Connection: Upgrade`, and the negotiated
`buddystudy.voice.control.v2` subprotocol must reach the backend. If a proxy
removes the upgrade headers, the backend rejects the handshake with HTTP 422;
do not weaken handshake validation or turn the rejected request into a normal
HTTP response. See [Cloudflare's WebSocket documentation](https://developers.cloudflare.com/network/websockets/).

When Routingflare/TunnelBar manages the connector, inspect the running
cloudflared process's generated configuration, not just
`~/.cloudflared/config.yaml`. An origin such as a local Routingflare proxy port
is an additional hop before the API. Compare the same unauthenticated diagnostic
request's upgrade headers at the direct API and proxy origins; HTTP 401 is
expected without credentials and does not itself indicate a broken upgrade.
Do not log access tokens or client secrets during this check. Any route repair
must retain its existing access policy and affect only the intended dev route.

## TCP access

Cloudflare Tunnel TCP hostnames are not direct public TCP sockets. Use
`cloudflared access tcp` locally, then connect your client to the local port:

```sh
cloudflared access tcp --hostname db.lowfidev.cloud --url localhost:13306
mysql -h 127.0.0.1 -P 13306 -u <user> -p buddystudy
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
