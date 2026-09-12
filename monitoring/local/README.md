# Local MCP monitoring

This independent stack reads only `backend-backend-1` Docker logs on the existing
`backend_default` network. Start the development backend first. It does not
change the production monitoring stack or restart the backend.

From the repository root:

```sh
npm --prefix monitoring/api-dashboard ci --no-audit --no-fund
mkdir -p monitoring/local/public
cp -R monitoring/api-dashboard/public/. monitoring/local/public/
VITE_LOCAL_MONITORING_NO_AUTH=true npm --prefix monitoring/api-dashboard run build -- --outDir ../local/public/react
docker compose -f monitoring/local/compose.json up -d
```

Open http://localhost:3000/performance.html and select Method `MCP`.
API Logs is at http://localhost:3000/ . No username or password is needed for
these local read-only monitoring pages. The opt-in build also requires a
loopback browser hostname; the production build retains administrator login.
The generated local bundle is ignored by Git and never replaces the versioned
production bundle. Backend administration APIs retain their authorization;
TestZone and Grafana are not started by this minimal stack.

Only host loopback ports 3000 and 3100 are published. Loki stores seven days of
logs in a local named volume. Promtail resumes from persisted offsets and drops
older entries. MCP rows appear after actual local MCP calls; starting the
monitor does not fabricate calls or invoke paid AI requests.

Stop with `docker compose -f monitoring/local/compose.json stop`.

Verification: loopback/opt-in guard tests, separate local and production builds,
HTTP page and unauthenticated local Loki reads, and container label isolation.

Verified locally: 132 dashboard tests passed. The browser opened API Performance
without credentials and reached Ready with MCP selected. Local Loki reads returned
200 without authorization; backend administrator session validation remained 401.
Only `backend-backend-1` appeared in the collected container labels. No recent MCP
rows existed at verification time. Local navigation excludes production links and
unstarted management workspaces.
