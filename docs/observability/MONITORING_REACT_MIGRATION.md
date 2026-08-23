# Monitoring React Migration

## Decision

The Monitoring UI is served through one React application. The migration kept
the stable public URLs and the proven Loki/TestZone controllers while moving
all rendered page composition, navigation, route selection, page headers, and
administrator state into the React root.

The target is one React application at `monitoring.lowfidev.cloud` with:

- one route boundary and fixed navigation shell;
- one administrator session store;
- query caching, cancellation, and explicit loading/error states;
- reusable tables, cursor pagination, time ranges, and chart components;
- the existing same-origin `/backend`, `/loki`, and `/testzone/api` contracts.

## Phase 1: Shared Boundaries

Status: complete.

- `src/admin/adminApi.js` is the single backend administrator token/API
  boundary.
- `Users & Quotas` and `Redis Streams` consume the same session provider.
- Redis Stream filtering, cursor construction, audit parsing, and nested object
  parsing live in pure modules with Node tests.

This removes the most error-prone duplicated state before introducing a
framework.

## Phase 2: React Shell and Manage Pages

Status: complete.

A Vite React application now lives inside `monitoring/api-dashboard` and
preserves
the current public paths. Migrate low-risk stateful pages first:

1. `Users & Quotas`
2. `Redis Streams`
3. `Settings`
4. `Access & Audit`

The application uses the browser pathname as the route boundary and TanStack
Query for server state. A client-side routing dependency is intentionally
unnecessary while each operational page retains a stable `.html` URL. Admin
credentials stay out of application state after login; only the
session-scoped bearer token is retained.

The shared Manage UI includes:

- a fixed compact/expanded navigation shell;
- dense, horizontally safe tables with bounded pagination;
- row-selected detail drawers instead of inline table forms;
- membership and quota edits isolated in the selected user drawer;
- Stream Entries, Event Outbox, and Push Outbox views;
- recursive JSON-string decoding with tree/raw object inspection;
- consistent loading, empty, error, and expired-session states.

## Phase 3: Observability Pages

Status: complete.

API Logs and API Performance now mount through the same React entry and fixed
application shell as Manage. Their existing bounded Loki queries, request
pagination, timeline selection, endpoint grouping, and detail rendering remain
behind page-specific controllers so the route migration does not change query
semantics.

Grafana remains the source for server/JVM/database/Redis dashboards. Monitoring
links to Grafana rather than recreating those panels.

## Phase 4: TestZone

Status: complete.

TestZone now mounts through the React route and common navigation shell. The
React page owns the workspace header and lifecycle boundary. The existing
contract-tested controller continues to own its editor, live run polling,
multi-scenario metrics, history detail, component controls, and uPlot chart
lifecycle. This preserves saved-script keyboard behavior and active-run
polling while removing the separate static navigation implementation.

## Rollout

- Serve one React entry from the existing Nginx image without changing backend
  endpoint paths.
- Preserve direct links and browser refresh for every migrated route.
- Run static tests plus browser interaction checks for login, pagination,
  refresh, and session expiry before switching a route.
- Keep page-specific controllers isolated behind React lifecycle boundaries
  until their behavior is replaced by equivalent hooks and component tests.

## Controller Boundary

React owns every visible route and the entire shared shell. API Logs,
API Performance, and TestZone still use isolated imperative controllers for
their high-frequency canvas, editor, and polling behavior. They no longer own
page routing, headers, navigation, or global styling. This boundary allows
future controller-to-hook refactors without another route or deployment
cutover.
