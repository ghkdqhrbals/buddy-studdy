# BuddyStudy Portfolio

Public engineering case study for BuddyStudy. The site explains the shipped
product, backend architecture, measured performance, reliability model,
security boundaries, verification strategy, and operational monitoring.

## Local Development

Requirements:

- Node.js 22.13 or newer
- npm

Commands:

```bash
npm install
npm run dev
npm test
npm run lint
npm audit --omit=dev
```

The local site is available at `http://localhost:3000`.

## Evidence Sources

The public claims are backed by repository artifacts rather than estimates:

- App Store iOS screenshots copied into `public/media/`
- k6 dashboard from
  `../backend/loadtest/results/20260721T060714Z-rps-sweep-final/`
- detailed benchmark report in
  `../docs/performance/MVC_VS_WEBFLUX_R2DBC_2026-07-22.md`
- runtime monitoring design in `../docs/observability/runtime-metrics.md`
- interview notes in `../docs/PORTFOLIO_INTERVIEW_GUIDE.md`
- Cloudflare private-network setup in `../deploy/cloudflared/README.md`

Measured results are labeled as current implementation results. Planned
optimizations are not presented as completed capacity.

## Deployment

- Public URL: https://buddystudy.lowfidev.cloud
- Origin: `vinext start` on `127.0.0.1:3011`
- Process supervisor: macOS `launchd`
- Public routing: Routingflare named Cloudflare Tunnel
- Sites fallback: https://buddystudy-portfolio.ghkdqhrbals.chatgpt.site

Apply or repair the local production route with:

```sh
./scripts/setup-routingflare.sh
```

The script builds the application, installs the tracked launch agent, waits for
the local production origin, registers the Routingflare route, restarts the
tunnel, and points the public hostname at the configured named tunnel.

This deployment intentionally depends on the Mac and its Routingflare tunnel
remaining online. The Sites deployment remains available as an independent
fallback URL. The site has no database, authentication, forms, runtime secrets,
or user-specific state.
