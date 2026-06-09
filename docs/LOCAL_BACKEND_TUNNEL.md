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

Cloudflare prints a public HTTPS URL like:

```text
https://example.trycloudflare.com
```

## 3. Point the iOS app at the tunnel

In BuddyStuddy on iPhone:

1. Open Settings.
2. Enable Debugging Mode.
3. Paste the Cloudflare HTTPS URL into Debug API URL.
4. Tap Save.

After saving, every backend API request made by the app uses the debug URL until Debugging Mode is turned off.

## Notes

- Do not use `localhost` or `127.0.0.1` in the iPhone app. Those point to the iPhone, not the Mac.
- Quick tunnel URLs change each time the tunnel starts. Update Debug API URL after restarting the tunnel.
- Keep production debugging off before App Store or TestFlight verification.
