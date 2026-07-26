# Monitoring React Migration

## Decision

The Monitoring UI can move to React. The change should be incremental rather
than a single replacement because API Logs, Grafana links, and TestZone are
already operational tools with different refresh, chart, editor, and
pagination behavior.

The target is one React application at `monitoring.lowfidev.cloud` with:

- one router and fixed navigation shell;
- one administrator session store;
- query caching, cancellation, and explicit loading/error states;
- reusable tables, cursor pagination, time ranges, and chart components;
- the existing same-origin `/backend`, `/loki`, and `/testzone/api` contracts.

## Phase 1: Shared Boundaries

Status: started.

- `admin-session.js` is the single backend administrator token/API boundary.
- `Users & Quotas` and `Redis Streams` consume that boundary.
- Redis Stream filtering and cursor path construction live in a pure model
  module with Node tests.

This removes the most error-prone duplicated state before introducing a
framework.

## Phase 2: React Shell and Manage Pages

Create a Vite React application inside `monitoring/api-dashboard` and preserve
the current public paths. Migrate low-risk stateful pages first:

1. `Users & Quotas`
2. `Redis Streams`
3. `Settings`
4. `Access & Audit`

Use React Router for page state and TanStack Query for server state. Keep admin
credentials out of application state after login; retain only the existing
session-scoped bearer token.

## Phase 3: Observability Pages

Move API Logs and API Performance after the shared time-range, pagination, and
uPlot wrappers exist. Preserve request cancellation so a new query cannot
render stale results from an older time range.

Grafana remains the source for server/JVM/database/Redis dashboards. Monitoring
links to Grafana rather than recreating those panels.

## Phase 4: TestZone

Migrate TestZone last. Its editor, live run polling, multi-scenario metrics,
history detail, components, and charts have the largest behavioral surface.
Move each feature behind contract tests before deleting the corresponding
static module.

## Rollout

- Serve React and existing static pages from the same Nginx image during the
  transition.
- Migrate one route at a time without changing backend endpoint paths.
- Preserve direct links and browser refresh for every migrated route.
- Run static tests plus browser interaction checks for login, pagination,
  refresh, and session expiry before switching a route.
- Remove a static implementation only after its React route is verified.

## Why Not a Big-Bang Rewrite

React improves state composition, but it does not automatically fix polling,
request races, or chart lifecycle problems. Replacing every page at once would
combine framework migration with operational behavior changes and make
production regressions harder to isolate.
