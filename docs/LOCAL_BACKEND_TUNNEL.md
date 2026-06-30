# Local Backend Tunnel

Use this when you want the iOS app to send API requests to a backend running on this Mac.

## 1. Run the local backend

```sh
cd backend
docker compose up --build
```

The local API should be available at:

```text
http://localhost:8080
```

## 2. Open a Cloudflare tunnel

From the repository root:

```sh
scripts/start-local-api-tunnel.sh
```

On this Mac, `~/.cloudflared/config.yaml` already defines a named tunnel:

```text
https://lowfidev.cloud -> http://localhost:8080
```

If no named tunnel config exists, Cloudflare prints a temporary HTTPS URL like:

```text
https://example.trycloudflare.com
```

## 3. Point the iOS app at the tunnel

In BuddyStudy on iPhone:

1. Open Settings.
2. Enable Debugging Mode.
3. Paste the Cloudflare HTTPS URL into Debug API URL. On this Mac, use `https://lowfidev.cloud`.
4. Tap Save.

After saving, every backend API request made by the app uses the debug URL until Debugging Mode is turned off.

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
- Quick tunnel URLs change each time the tunnel starts. Named tunnel URLs such as `https://lowfidev.cloud` do not.
- The tunnel can be healthy while the backend is down. In that case Cloudflare returns 502 until `localhost:8080` is running.
- Keep production debugging off before App Store or TestFlight verification.
