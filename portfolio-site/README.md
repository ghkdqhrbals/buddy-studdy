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

The site is hosted with OpenAI Sites. `.openai/hosting.json` contains the opaque
project ID used by the Sites deployment flow. A deployable version must be
created from the exact Git commit pushed to the Sites source repository.

Production hostname:

```text
https://buddystudy.lowfidev.cloud
```

Access mode is public. The site has no database, authentication, forms, runtime
secrets, or user-specific state.
